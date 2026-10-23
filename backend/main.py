import os
import re
import json
import logging
import requests
import copy
import time
import random
import asyncio
import base64
import concurrent.futures
import functions_framework
import vertexai
from vertexai.generative_models import GenerativeModel, Part, GenerationConfig

# Configure Logging
logging.basicConfig(level=logging.INFO)

# ===================================================================================
# THE DEBUG SWITCH & VERTEX AI INITIALIZATION
# ===================================================================================
env_debug = os.environ.get("DEBUG_MODE", "False").strip().lower()
DEBUG_MODE = env_debug in ["true", "1", "t", "yes", "y"]

PROJECT_ID = os.environ.get("GOOGLE_CLOUD_PROJECT") or os.environ.get("PROJECT_ID") or "filterpoint-backend"
LOCATION = os.environ.get("GOOGLE_CLOUD_REGION") or "us-central1"

# Production-verified active GA models
ACTIVE_MODELS = ["gemini-2.5-flash", "gemini-2.5-flash-lite"]

try:
    vertexai.init(project=PROJECT_ID, location=LOCATION)
    logging.info(f"[VERTEX AI INIT] Vertex AI initialized successfully on project '{PROJECT_ID}' in location '{LOCATION}'.")
except Exception as e:
    logging.error(f"[VERTEX AI INIT ERROR] Failed to initialize Vertex AI: {e}")

# ===================================================================================
# CONFIGURATION, FIRESTORE, FIREBASE AUTH, & CONNECTION POOLING
# ===================================================================================
USDA_API_KEY = os.environ.get("USDA_API_KEY", "DEMO_KEY")
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "")
USDA_CACHE = {}

# Thread-safe persistent HTTP session
http_session = requests.Session()
pool_adapter = requests.adapters.HTTPAdapter(
    pool_connections=20,
    pool_maxsize=100,
    max_retries=1
)
http_session.mount("https://", pool_adapter)
http_session.mount("http://", pool_adapter)

# Dynamic Lazy Import of Firestore
db = None
try:
    from google.cloud import firestore
    db = firestore.Client()
    logging.info("[FIRESTORE INIT] Firestore Client successfully initialized.")
except Exception as e:
    logging.error(f"[FIRESTORE ERROR] Firestore init skipped: {e}. In-memory cache active.")
    db = None

# Dynamic Lazy Import of Firebase Admin
firebase_admin_active = False
try:
    import firebase_admin
    from firebase_admin import auth
    if not firebase_admin._apps:
        firebase_admin.initialize_app()
    firebase_admin_active = True
    logging.info("[FIREBASE ADMIN INIT] Firebase Admin successfully initialized.")
except Exception as e:
    logging.error(f"[FIREBASE ADMIN ERROR] Firebase Admin init skipped: {e}")

def get_authenticated_uid(request) -> str:
    if not firebase_admin_active:
        return "DEV_BYPASS"
    auth_header = request.headers.get("Authorization")
    if not auth_header or not auth_header.startswith("Bearer "):
        return None
    token = auth_header.split("Bearer ")[1].strip()
    if not token or token in ["null", "undefined"]:
        return None
    try:
        decoded_token = auth.verify_id_token(token)
        return decoded_token.get("uid")
    except Exception as e:
        logging.warning(f"[AUTH VERIFY] Optional token verification notice: {e}")
        return None

# ===================================================================================
# GLOBAL SCHEMAS & MEASUREMENT MAPPINGS
# ===================================================================================
UNIT_GRAMS_MAP = {
    "g": 1.0, "gram": 1.0, "grams": 1.0, "kg": 1000.0, "kilogram": 1000.0, "kilograms": 1000.0,
    "lb": 453.592, "lbs": 453.592, "pound": 453.592, "pounds": 453.592, "oz": 28.3495, "ozs": 28.3495,
    "ounce": 28.3495, "ounces": 28.3495, "cup": 240.0, "cups": 240.0, "tbsp": 15.0, "tbsps": 15.0,
    "tablespoon": 15.0, "tablespoons": 15.0, "tsp": 5.0, "tsps": 5.0, "teaspoon": 5.0, "teaspoons": 5.0,
    "clove": 3.0, "cloves": 3.0, "pinch": 0.5, "pinches": 0.5,
    "dash": 0.5, "dashes": 0.5, "drop": 0.05, "drops": 0.05, "sprig": 1.0, "sprigs": 1.0,
    "unit": 100.0, "units": 100.0, "piece": 100.0, "pieces": 100.0, "slice": 30.0, "slices": 30.0
}

NUTRIENT_MAPPING = {
    "calories": {"ids": [1008], "keywords": ["energy"], "unit_blacklist": ["kj"]},
    "sodium_mg": {"ids": [1093], "keywords": ["sodium"]},
    "potassium_mg": {"ids": [1092], "keywords": ["potassium"]},
    "protein_g": {"ids": [1003], "keywords": ["protein"]},
    "total_carbohydrates_g": {"ids": [1005], "keywords": ["carbohydrate"]},
    "fiber_g": {"ids": [1079], "keywords": ["fiber"]},
    "total_sugar_g": {"ids": [2000, 1010], "keywords": ["sugars, total", "total sugar"]},
    "saturated_fat_g": {"ids": [1258], "keywords": ["saturated", "fatty acids, total saturated"]},
    "total_fat_g": {"ids": [1004], "keywords": ["lipid", "total fat"]}
}

FRONTEND_NUTRIENT_MAP = {
    "potassium": "potassium_mg",
    "carbs": "total_carbohydrates_g",
    "sodium": "sodium_mg",
    "saturated_fat": "saturated_fat_g",
    "protein": "protein_g",
    "calories": "calories",
    "fiber": "fiber_g",
    "sugar": "total_sugar_g",
    "total_sugar": "total_sugar_g",
    "added_sugar": "total_sugar_g",
    "fat": "total_fat_g",
    "total_fat": "total_fat_g"
}

QUERY_NORMALIZATION_MAP = {
    "cooked brown or white rice": "cooked brown rice",
    "cooked white or brown rice": "cooked white rice",
    "brown or white rice": "cooked brown rice",
    "white or brown rice": "cooked white rice",
    "brown rice": "cooked brown rice",
    "white rice": "cooked white rice",
    "frozen broccoli": "broccoli",
    "broccoli stir fry vegetable blend": "broccoli",
    "broccoli stir-fry vegetable blend": "broccoli"
}

# ===================================================================================
# HIGH-SPEED IN-MEMORY COMMODITY INTERCEPTOR (0ms Latency Table)
# ===================================================================================
DETERMINISTIC_INTERCEPTOR = {
    "cassava flour": {
        "description": "Cassava flour, whole root (AIP grain-free & nut-free staple)",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 360.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 1.5, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 15.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 300.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 86.0, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 6.0, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 1.5, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 0.3, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 0.05, "unitName": "G"}
        ]
    },
    "nomato sauce": {
        "description": "Nomato sauce (AIP nightshade-free beet, carrot & garlic puree)",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 45.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 1.2, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 35.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 280.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 9.5, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 2.5, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 5.0, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 0.5, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 0.1, "unitName": "G"}
        ]
    },
    "cassava tortilla": {
        "description": "Tortillas, cassava flour (AIP grain-free, corn-free)",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 210.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 1.0, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 180.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 120.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 45.0, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 3.0, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 1.0, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 4.0, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 0.5, "unitName": "G"}
        ]
    },
    "cassava pizza crust": {
        "description": "Crust, pizza, cassava-based, AIP-compliant grain-free",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 220.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 1.5, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 210.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 160.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 48.0, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 3.5, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 1.0, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 3.5, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 0.5, "unitName": "G"}
        ]
    },
    "sweet potatoes": {
        "description": "Sweet potato, raw, unprepared (AIP non-nightshade tuber)",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 86.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 1.57, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 55.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 337.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 20.12, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 3.0, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 4.18, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 0.05, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 0.02, "unitName": "G"}
        ]
    },
    "pure maple syrup": {
        "description": "Syrups, maple, pure (AIP-compliant natural sweetener)",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 260.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 0.04, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 12.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 212.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 67.0, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 60.5, "unitName": "G"}
        ]
    },
    "1:1 gluten-free baking flour blend": {
        "description": "1:1 Gluten-Free Baking Flour Blend (Standard Commercial)",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 360.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 4.0, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 10.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 50.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 82.0, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 4.0, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 0.0, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 1.0, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 0.0, "unitName": "G"}
        ]
    },
    "all-purpose flour": {
        "description": "Wheat flour, white, all-purpose, unenriched",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 364.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 10.33, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 2.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 107.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 76.31, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 2.7, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 0.27, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 0.98, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 0.15, "unitName": "G"}
        ]
    },
    "low-carb tortilla": {
        "description": "Tortillas, low-carb / keto high-fiber",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 160.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 11.0, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 450.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 120.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 34.0, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 26.0, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 0.0, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 5.0, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 0.5, "unitName": "G"}
        ]
    },
    "white corn tortilla": {
        "description": "Tortillas, ready-to-bake or -fry, corn",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 218.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 5.7, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 45.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 185.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 44.6, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 6.3, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 0.8, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 2.9, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 0.4, "unitName": "G"}
        ]
    },
    "gluten-free pizza crust": {
        "description": "Crust, pizza, gluten-free, prepared",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 245.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 3.2, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 390.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 85.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 49.0, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 2.5, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 2.0, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 4.5, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 0.6, "unitName": "G"}
        ]
    },
    "cauliflower pizza crust": {
        "description": "Crust, pizza, cauliflower-based, low-carb",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 180.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 9.5, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 360.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 240.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 16.0, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 3.5, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 2.0, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 8.5, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 3.5, "unitName": "G"}
        ]
    },
    "baking soda": {
        "description": "Leavening agents, baking soda (Sodium Bicarbonate)",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 0.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 0.0, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 27360.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 0.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 0.0, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 0.0, "unitName": "G"}
        ]
    },
    "baking powder": {
        "description": "Leavening agents, baking powder, double-acting, sodium aluminum sulfate",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 53.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 0.0, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 10600.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 20.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 27.7, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 0.0, "unitName": "G"}
        ]
    },
    "lemon juice": {
        "description": "Lemon juice, raw (Natural Acid Leavening Activator)",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 22.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 0.35, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 1.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 103.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 6.9, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 2.52, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 0.24, "unitName": "G"}
        ]
    },
    "distilled white vinegar": {
        "description": "Vinegar, distilled white (Neutral Acid Leavening Activator)",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 18.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 0.0, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 2.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 2.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 0.04, "unitName": "G"}
        ]
    },
    "erythritol sweetener": {
        "description": "Erythritol Sweetener (Clinical Non-Glycemic Sugar Alternative)",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 0.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 0.0, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 0.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 0.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 100.0, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 0.0, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 0.0, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 0.0, "unitName": "G"}
        ]
    },
    "granulated sugar": {
        "description": "Sugars, granulated",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 387.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 0.0, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 1.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 2.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 100.0, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 100.0, "unitName": "G"}
        ]
    },
    "no salt added soy sauce": {
        "description": "Coconut Aminos (Clinical Low-Sodium Soy Alternative)",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 60.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 0.0, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 90.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 16.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 15.0, "unitName": "G"}
        ]
    },
    "no salt added soy sauce (coconut free)": {
        "description": "Soy-Based No-Salt Soy Alternative (Coconut-Free)",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 60.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 1.0, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 90.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 16.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 14.0, "unitName": "G"}
        ]
    },
    "reduced sodium soy sauce": {
        "description": "Soy sauce, low sodium",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 53.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 6.1, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 3496.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 149.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 5.7, "unitName": "G"}
        ]
    },
    "no salt added chicken broth": {
        "description": "Chicken broth, low sodium, canned",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 6.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 0.3, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 24.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 8.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 1.1, "unitName": "G"}
        ]
    },
    "cooked brown rice": {
        "description": "Rice, brown, long-grain, cooked",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 123.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 2.74, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 4.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 86.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 25.64, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 1.6, "unitName": "G"}
        ]
    },
    "cooked white rice": {
        "description": "Rice, white, long-grain, regular, cooked",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 130.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 2.69, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 4.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 35.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 28.17, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 0.4, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 0.05, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 0.28, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 0.08, "unitName": "G"}
        ]
    },
    "cooked pasta": {
        "description": "Pasta, cooked, unenriched, without added salt",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 131.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 5.15, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 6.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 24.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 24.93, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 1.4, "unitName": "G"}
        ]
    },
    "raw potatoes": {
        "description": "Potatoes, white, flesh and skin, raw",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 77.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 2.05, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 6.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 421.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 17.47, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 2.1, "unitName": "G"}
        ]
    },
    "riced cauliflower": {
        "description": "Cauliflower, raw, chopped / riced",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 25.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 1.92, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 30.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 299.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 4.97, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 2.0, "unitName": "G"}
        ]
    },
    "raw salmon": {
        "description": "Fish, salmon, Atlantic, raw",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 142.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 19.84, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 44.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 490.0, "unitName": "MG"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 1.25, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 6.34, "unitName": "G"}
        ]
    },
    "chicken breast": {
        "description": "Chicken breast, meat only, raw",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 120.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 22.5, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 45.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 256.0, "unitName": "MG"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 2.6, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 0.6, "unitName": "G"}
        ]
    },
    "unsalted butter": {
        "description": "Butter, unsalted",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 717.0, "unitName": "KCAL"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 81.11, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 51.37, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 11.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 24.0, "unitName": "MG"}
        ]
    },
    "butter": {
        "description": "Butter, salted",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 717.0, "unitName": "KCAL"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 81.11, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 51.37, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 643.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 24.0, "unitName": "MG"}
        ]
    },
    "olive oil": {
        "description": "Oil, olive, salad or cooking",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 884.0, "unitName": "KCAL"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 100.0, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 13.8, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 2.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 1.0, "unitName": "MG"}
        ]
    },
    "table salt": {
        "description": "Salt, table",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 0.0, "unitName": "KCAL"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 38758.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 8.0, "unitName": "MG"}
        ]
    },
    "raw garlic": {
        "description": "Garlic, raw",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 149.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 6.36, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 17.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 401.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 33.06, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 2.1, "unitName": "G"}
        ]
    },
    "sirloin beef": {
        "description": "Beef, top sirloin, steak, separable lean only, trimmed to 0 inch fat, raw",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 142.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 22.4, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 54.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 355.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 0.0, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 0.0, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 5.2, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 1.9, "unitName": "G"}
        ]
    },
    "broccoli": {
        "description": "Broccoli, raw, florets and stalks",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 34.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 2.82, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 33.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 316.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 6.64, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 2.6, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 1.7, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 0.37, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 0.04, "unitName": "G"}
        ]
    },
    "roma tomato": {
        "description": "Tomatoes, red, ripe, raw, year round average",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 18.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 0.88, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 5.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 237.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 3.89, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 1.2, "unitName": "G"},
            {"nutrientId": 2000, "nutrientName": "Sugars, total", "value": 2.63, "unitName": "G"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 0.2, "unitName": "G"}
        ]
    },
    "soy sauce": {
        "description": "Soy sauce made from soy and wheat (shoyu)",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 53.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 8.14, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 5493.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 212.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 4.93, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 0.8, "unitName": "G"}
        ]
    },
    "peanut oil": {
        "description": "Oil, peanut, salad or cooking",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 884.0, "unitName": "KCAL"},
            {"nutrientId": 1004, "nutrientName": "Total lipid (fat)", "value": 100.0, "unitName": "G"},
            {"nutrientId": 1258, "nutrientName": "Fatty acids, total saturated", "value": 16.9, "unitName": "G"}
        ]
    },
    "chicken broth": {
        "description": "Soup, chicken broth or bouillon, ready-to-serve",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 15.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 1.59, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 399.0, "unitName": "MG"},
            {"nutrientId": 1092, "nutrientName": "Potassium, K", "value": 78.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 0.52, "unitName": "G"}
        ]
    },
    "cornstarch": {
        "description": "Cornstarch",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 381.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 0.26, "unitName": "G"},
            {"nutrientId": 1093, "nutrientName": "Sodium, Na", "value": 9.0, "unitName": "MG"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 91.27, "unitName": "G"},
            {"nutrientId": 1079, "nutrientName": "Fiber, total dietary", "value": 0.9, "unitName": "G"}
        ]
    }
}

LOCAL_FALLBACKS = {
    "salt": DETERMINISTIC_INTERCEPTOR["table salt"],
    "sugar": DETERMINISTIC_INTERCEPTOR["granulated sugar"],
    "oil": DETERMINISTIC_INTERCEPTOR["olive oil"],
    "butter": DETERMINISTIC_INTERCEPTOR["butter"],
    "garlic": DETERMINISTIC_INTERCEPTOR["raw garlic"],
    "chicken": DETERMINISTIC_INTERCEPTOR["chicken breast"]
}

# ===================================================================================
# THE PAYLOAD STRIPPER (DATA DIET ENGINE)
# ===================================================================================
def strip_food_payload(food_item: dict) -> dict:
    if not food_item:
        return {}
    stripped = {
        "description": food_item.get("description", "Stripped USDA Food Item"),
        "foodNutrients": []
    }
    target_ids = set()
    for config in NUTRIENT_MAPPING.values():
        target_ids.update(config["ids"])
    for nutr in food_item.get("foodNutrients", []):
        nid = nutr.get("nutrientId")
        if nid in target_ids:
            stripped["foodNutrients"].append({
                "nutrientId": nid,
                "nutrientName": nutr.get("nutrientName", ""),
                "value": float(nutr.get("value", 0.0)),
                "unitName": nutr.get("unitName", "")
            })
    return stripped

# ===================================================================================
# FRACTION CONVERTER & CULINARY FORMATTING UTILITIES
# ===================================================================================
def format_quantity_to_fraction(val: float) -> str:
    """Converts float quantities to clean, practical US kitchen fractions."""
    if val is None or val <= 0.0:
        return "0"
    whole = int(val)
    remainder = val - whole
    fraction_map = [
        (0.000, 0.0625, ""),
        (0.0625, 0.1875, "1/8"),
        (0.1875, 0.2915, "1/4"),
        (0.2915, 0.4165, "1/3"),
        (0.4165, 0.5835, "1/2"),
        (0.5835, 0.7085, "2/3"),
        (0.7085, 0.8125, "3/4"),
        (0.8125, 0.9375, "7/8"),
        (0.9375, 1.0000, "1")
    ]
    frac_str = ""
    for low, high, rep in fraction_map:
        if low <= remainder < high:
            frac_str = rep
            break
    if frac_str == "1" or remainder >= 0.9375:
        whole += 1
        frac_str = ""
    if whole > 0 and frac_str:
        return f"{whole} {frac_str}"
    elif whole > 0:
        return f"{whole}"
    elif frac_str:
        return f"{frac_str}"
    else:
        return "1/8" if val > 0 else "0"

def parse_fraction_to_float(val_str) -> float:
    if val_str is None:
        return 0.0
    val_str = str(val_str).strip()
    try:
        return float(val_str)
    except ValueError:
        pass
    unicode_fractions = {
        "½": 0.5, "⅓": 0.333, "⅔": 0.666, "¼": 0.25, "¾": 0.75,
        "⅕": 0.2, "⅖": 0.4, "⅗": 0.6, "⅘": 0.8, "⅙": 0.166,
        "⅚": 0.833, "⅛": 0.125, "⅜": 0.375, "⅝": 0.625, "⅞": 0.875
    }
    if val_str in unicode_fractions:
        return unicode_fractions[val_str]
    if "/" in val_str:
        try:
            parts = val_str.split("/")
            if len(parts) == 2:
                return float(parts[0].strip()) / float(parts[1].strip())
        except Exception:
            pass
    compound_match = re.match(r"^(\d+)\s+([½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞])$", val_str)
    if compound_match:
        try:
            return float(compound_match.group(1)) + unicode_fractions[compound_match.group(2)]
        except Exception:
            pass
    slashed_compound_match = re.match(r"^(\d+)\s+(\d+)/(\d+)$", val_str)
    if slashed_compound_match:
        try:
            return float(slashed_compound_match.group(1)) + (float(slashed_compound_match.group(2)) / float(slashed_compound_match.group(3)))
        except Exception:
            pass
    return 1.0

def unit_to_grams(quantity: float, unit: str) -> float:
    unit_lower = str(unit or "").lower().strip()
    if not quantity:
        return 0.0
    if unit_lower in UNIT_GRAMS_MAP:
        return quantity * UNIT_GRAMS_MAP[unit_lower]
    return quantity * 100.0

def adjust_weight_by_culinary_density(clean_name: str, unit: str, quantity: float, calculated_weight: float) -> float:
    name_lower = clean_name.lower()
    unit_lower = str(unit or "").lower().strip()

    spice_keywords = [
        "pepper", "cayenne", "paprika", "cinnamon", "nutmeg", "salt", "oregano", 
        "basil", "dill", "thyme", "rosemary", "cumin", "coriander", "allspice", 
        "clove", "ginger", "cardamom", "turmeric", "bay leaf", "curry powder"
    ]
    if any(sp in name_lower for sp in spice_keywords):
        if unit_lower in ["unit", "piece", "pieces", ""]:
            return quantity * 0.5
        elif unit_lower in ["dash", "dashes"]:
            return quantity * 0.5
        elif unit_lower in ["pinch", "pinches"]:
            return quantity * 0.3

    if any(herb in name_lower for herb in ["parsley", "cilantro", "basil", "dill", "thyme", "mint", "oregano"]):
        if "cup" in unit_lower:
            return quantity * 40.0
    if "juice" in name_lower and ("lemon" in name_lower or "lime" in name_lower):
        if "unit" in unit_lower or not unit_lower:
            return quantity * 30.0
    if ("zest" in name_lower or "peel" in name_lower) and ("lemon" in name_lower or "lime" in name_lower):
        if "unit" in unit_lower or not unit_lower:
            return quantity * 2.0
    if "garlic" in name_lower and ("clove" in unit_lower or "unit" in unit_lower):
        return quantity * 3.0

    return calculated_weight

def sanitize_search_query(name: str, original_text: str = "") -> str:
    if not name:
        return ""
    name_clean = name.lower().strip()
    if name_clean in QUERY_NORMALIZATION_MAP:
        name_clean = QUERY_NORMALIZATION_MAP[name_clean]
    if " or " in name_clean:
        parts = name_clean.split(" or ")
        name_clean = parts[0].strip()
    original_lower = original_text.lower() if original_text else name_clean
    cooked_terms = ["rice", "pasta", "noodle", "noodles", "spaghetti", "macaroni", "quinoa", "barley", "oats", "lentil", "lentils", "bean", "beans", "pea", "peas"]
    preserve_cooked = "cooked" in original_lower and any(term in name_clean for term in cooked_terms)
    name_clean = re.sub(r"\(.*?\)", "", name_clean).strip()
    descriptors = [
        "large", "medium", "small", "extra large", "jumbo", "fresh", "raw", "dry", "dried",
        "organic", "sliced", "chopped", "minced", "peeled", "deveined", "divided",
        "diced", "shredded", "packed", "ground", "crushed", "pureed", "canned",
        "strictly", "cloves", "clove", "cups", "cup", "teaspoon", "teaspoons", "tsp", 
        "tablespoon", "tablespoons", "tbsp"
    ]
    if not preserve_cooked:
        descriptors.append("cooked")
    for desc in descriptors:
        name_clean = re.sub(r'\b' + re.escape(desc) + r'\b', '', name_clean)
    if preserve_cooked and "cooked" not in name_clean:
        name_clean = "cooked " + name_clean
    if "sirloin" in name_clean and "veal" not in name_clean:
        name_clean = "sirloin beef"
    name_clean = re.sub(r'[^\w\s-]', '', name_clean)
    name_clean = " ".join(name_clean.split()).strip()
    return name_clean if name_clean else name.lower().strip()

def get_fuzzy_intercept_profile(clean_name: str, allergies: list) -> dict:
    name_lower = str(clean_name or "").lower().strip().replace("-", " ")
    allergies_list = allergies or []
    has_coconut_allergy = any(str(a or "").lower().strip() == "coconut" for a in allergies_list)

    # AIP Commodities
    if any(kw in name_lower for kw in ["cassava flour", "tapioca flour", "arrowroot starch", "tigernut flour"]):
        return DETERMINISTIC_INTERCEPTOR["cassava flour"]
    if any(kw in name_lower for kw in ["nomato sauce", "nomato"]):
        return DETERMINISTIC_INTERCEPTOR["nomato sauce"]
    if any(kw in name_lower for kw in ["cassava tortilla", "cassava tortillas"]):
        return DETERMINISTIC_INTERCEPTOR["cassava tortilla"]
    if any(kw in name_lower for kw in ["cassava pizza crust", "aip pizza crust"]):
        return DETERMINISTIC_INTERCEPTOR["cassava pizza crust"]
    if any(kw in name_lower for kw in ["sweet potato", "sweet potatoes", "yam", "yams"]):
        return DETERMINISTIC_INTERCEPTOR["sweet potatoes"]
    if any(kw in name_lower for kw in ["maple syrup", "pure maple syrup"]):
        return DETERMINISTIC_INTERCEPTOR["pure maple syrup"]

    # Gluten-Free & Discrete Vessels
    if any(kw in name_lower for kw in ["1:1 gluten-free", "gluten free flour", "gf flour blend", "gluten-free baking flour"]):
        return DETERMINISTIC_INTERCEPTOR["1:1 gluten-free baking flour blend"]
    if any(kw in name_lower for kw in ["low-carb tortilla", "keto tortilla", "low carb tortilla"]):
        return DETERMINISTIC_INTERCEPTOR["low-carb tortilla"]
    if any(kw in name_lower for kw in ["corn tortilla", "white corn tortilla"]):
        return DETERMINISTIC_INTERCEPTOR["white corn tortilla"]
    if any(kw in name_lower for kw in ["gluten-free pizza crust", "gf pizza crust"]):
        return DETERMINISTIC_INTERCEPTOR["gluten-free pizza crust"]
    if any(kw in name_lower for kw in ["cauliflower pizza crust", "cauliflower crust"]):
        return DETERMINISTIC_INTERCEPTOR["cauliflower pizza crust"]

    # Leaveners & Acids
    if any(kw in name_lower for kw in ["baking soda", "sodium bicarbonate"]):
        return DETERMINISTIC_INTERCEPTOR["baking soda"]
    if any(kw in name_lower for kw in ["baking powder"]):
        return DETERMINISTIC_INTERCEPTOR["baking powder"]
    if any(kw in name_lower for kw in ["lemon juice", "lime juice"]):
        return DETERMINISTIC_INTERCEPTOR["lemon juice"]
    if any(kw in name_lower for kw in ["distilled vinegar", "white vinegar", "vinegar"]):
        return DETERMINISTIC_INTERCEPTOR["distilled white vinegar"]

    # Sweeteners
    if any(kw in name_lower for kw in ["erythritol", "allulose", "monk fruit", "stevia"]):
        return DETERMINISTIC_INTERCEPTOR["erythritol sweetener"]
    if any(kw in name_lower for kw in ["sugar", "granulated sugar", "brown sugar"]):
        return DETERMINISTIC_INTERCEPTOR["granulated sugar"]

    # Sauces & Broths
    if any(kw in name_lower for kw in ["soy sauce", "tamari", "aminos"]):
        if any(mod in name_lower for mod in ["no salt", "unsalted", "zero sodium", "alternative", "free"]):
            if has_coconut_allergy:
                return DETERMINISTIC_INTERCEPTOR["no salt added soy sauce (coconut free)"]
            return DETERMINISTIC_INTERCEPTOR["no salt added soy sauce"]
        if any(mod in name_lower for mod in ["low", "reduced", "less"]):
            return DETERMINISTIC_INTERCEPTOR["reduced sodium soy sauce"]
        return DETERMINISTIC_INTERCEPTOR["soy sauce"]
    if any(kw in name_lower for kw in ["chicken broth", "broth", "stock", "bouillon"]):
        if any(mod in name_lower for mod in ["no salt", "unsalted", "zero sodium", "free", "low", "reduced", "less"]):
            return DETERMINISTIC_INTERCEPTOR["no salt added chicken broth"]
        return DETERMINISTIC_INTERCEPTOR["chicken broth"]

    # Grains & Starches (Defaulting meal white rice to cooked white rice)
    if "rice" in name_lower:
        if "brown" in name_lower:
            return DETERMINISTIC_INTERCEPTOR["cooked brown rice"]
        return DETERMINISTIC_INTERCEPTOR["cooked white rice"]
    if any(kw in name_lower for kw in ["pasta", "noodle", "spaghetti", "macaroni"]):
        return DETERMINISTIC_INTERCEPTOR["cooked pasta"]
    if "cornstarch" in name_lower or "corn starch" in name_lower:
        return DETERMINISTIC_INTERCEPTOR["cornstarch"]
    if "cauliflower" in name_lower:
        return DETERMINISTIC_INTERCEPTOR["riced cauliflower"]
    if "potato" in name_lower or "potatoes" in name_lower:
        return DETERMINISTIC_INTERCEPTOR["raw potatoes"]

    # Proteins & Produce
    if "sirloin" in name_lower or "beef" in name_lower:
        return DETERMINISTIC_INTERCEPTOR["sirloin beef"]
    if "broccoli" in name_lower:
        return DETERMINISTIC_INTERCEPTOR["broccoli"]
    if "roma tomato" in name_lower or "tomato" in name_lower:
        return DETERMINISTIC_INTERCEPTOR["roma tomato"]
    if "salmon" in name_lower:
        return DETERMINISTIC_INTERCEPTOR["raw salmon"]
    if "chicken" in name_lower:
        return DETERMINISTIC_INTERCEPTOR["chicken breast"]

    # Fats & Seasonings
    if "butter" in name_lower:
        if "unsalted" in name_lower:
            return DETERMINISTIC_INTERCEPTOR["unsalted butter"]
        return DETERMINISTIC_INTERCEPTOR["butter"]
    if "peanut oil" in name_lower:
        return DETERMINISTIC_INTERCEPTOR["peanut oil"]
    if "olive oil" in name_lower:
        return DETERMINISTIC_INTERCEPTOR["olive oil"]
    if "garlic" in name_lower:
        return DETERMINISTIC_INTERCEPTOR["raw garlic"]
    if "salt" in name_lower:
        return DETERMINISTIC_INTERCEPTOR["table salt"]
    if any(fl in name_lower for fl in ["flour", "all purpose flour", "wheat flour"]):
        return DETERMINISTIC_INTERCEPTOR["all-purpose flour"]

    return None

def get_local_fallback(clean_name: str) -> dict:
    name_lower = clean_name.lower()
    for key, val in LOCAL_FALLBACKS.items():
        if key in name_lower:
            return val
    return {
        "description": f"Fallback: {clean_name}",
        "foodNutrients": [
            {"nutrientId": 1008, "nutrientName": "Energy", "value": 100.0, "unitName": "KCAL"},
            {"nutrientId": 1003, "nutrientName": "Protein", "value": 2.0, "unitName": "G"},
            {"nutrientId": 1005, "nutrientName": "Carbohydrate, by difference", "value": 10.0, "unitName": "G"}
        ]
    }

# ===================================================================================
# USDA FDC RESOLUTION ENGINE
# ===================================================================================
def filter_unwanted_food_types(food_items: list, clean_query: str) -> list:
    filtered = []
    clean_query_lower = clean_query.lower()
    blacklist_terms = [
        "fried", "breaded", "battered", "restaurant", "fast food", "denny's", "mcdonald's", "kfc", "subway",
        "pizza", "burger", "coated", "gravy", "scampi", "casserole", "soup", "cracker", "chips", "crisps",
        "bouillon", "seasoning", "powdered", "marinade", "dip", "cured", "smoked", "marinated", "pickled",
        "canned", "bakery", "doughnut", "donut", "mayonnaise", "dressing", "spread", "syrup", "dinner",
        "meal", "entree", "lasagna", "ravioli", "taco", "burrito", "hot dog", "candy", "marshmallow",
        "sausage", "bologna", "salami", "pepperoni"
    ]
    for item in food_items:
        desc_lower = item.get("description", "").lower()
        category_lower = item.get("foodCategory", "").lower()
        should_skip = False
        for term in blacklist_terms:
            has_term = re.search(r'\b' + re.escape(term) + r'\b', desc_lower) is not None
            has_term_in_query = re.search(r'\b' + re.escape(term) + r'\b', clean_query_lower) is not None
            if has_term and not has_term_in_query:
                should_skip = True
                break
        if should_skip:
            continue
        if ("restaurant" in category_lower or "fast food" in category_lower) and "restaurant" not in clean_query_lower:
            continue
        filtered.append(item)
    return filtered

def select_proper_match(food_items: list, original_name: str) -> dict:
    if not food_items:
        return {}
    original_lower = original_name.lower()
    modifiers = ["unsalted", "no salt added", "low sodium", "sodium free", "un-salted"]
    active_modifiers = [m for m in modifiers if m in original_lower]
    if active_modifiers:
        for item in food_items:
            desc_lower = item.get("description", "").lower()
            if any(m in desc_lower for m in active_modifiers):
                return item
    return food_items[0]

def query_usda_api(clean_name: str, conditions: list, original_name: str, allergies: list, bypass_firestore: bool = False) -> dict:
    cache_key = str(clean_name or "").lower().strip()
    fuzzy_match = get_fuzzy_intercept_profile(clean_name, allergies)
    if fuzzy_match:
        return fuzzy_match
    if not DEBUG_MODE:
        if cache_key in USDA_CACHE:
            return USDA_CACHE[cache_key]
        if not bypass_firestore and db:
            try:
                doc_id = cache_key.replace("/", "_")
                doc_ref = db.collection("usda_cache").document(doc_id)
                doc = doc_ref.get()
                if doc.exists:
                    cached_food = doc.to_dict()
                    return cached_food
            except Exception:
                pass

    url = f"https://api.nal.usda.gov/fdc/v1/foods/search?api_key={USDA_API_KEY}"
    headers = {"Content-Type": "application/json"}
    payload = {"query": clean_name, "pageSize": 5, "dataType": ["Foundation", "SR Legacy"]}
    try:
        response = http_session.post(url, json=payload, headers=headers, timeout=8)
        if response.status_code == 200:
            data = response.json()
            foods = data.get("foods", [])
            if foods:
                clean_foods = filter_unwanted_food_types(foods, clean_name)
                selected_food = select_proper_match(clean_foods if clean_foods else foods, original_name)
                stripped_food = strip_food_payload(selected_food)
                if not DEBUG_MODE:
                    if not bypass_firestore and db:
                        try:
                            doc_id = cache_key.replace("/", "_")
                            db.collection("usda_cache").document(doc_id).set(stripped_food)
                        except Exception:
                            pass
                    USDA_CACHE[cache_key] = stripped_food
                return stripped_food
    except Exception:
        pass
    fallback = get_local_fallback(clean_name)
    if not DEBUG_MODE:
        USDA_CACHE[cache_key] = fallback
    return fallback

def prefetch_resolved_foods(parsed_ingredients: list, conditions: list, allergies: list) -> dict:
    resolved = {}
    if not parsed_ingredients:
        return resolved
    unique_names = set()
    name_to_original = {}
    for ing in parsed_ingredients:
        if not ing:
            continue
        qty = parse_fraction_to_float(ing.get("quantity", 1.0))
        unit = ing.get("unit", "")
        clean_name = ing.get("clean_name", "")
        original_text = ing.get("original_text", "")
        if unit_to_grams(qty, unit) <= 0.0:
            continue
        sanitized_name = sanitize_search_query(clean_name, original_text)
        unique_names.add(sanitized_name)
        if sanitized_name not in name_to_original:
            name_to_original[sanitized_name] = original_text if original_text else clean_name

    remaining_names = []
    for name in unique_names:
        fuzzy_match = get_fuzzy_intercept_profile(name, allergies)
        if fuzzy_match:
            resolved[name] = fuzzy_match
            continue
        if not DEBUG_MODE and name in USDA_CACHE:
            resolved[name] = USDA_CACHE[name]
            continue
        remaining_names.append(name)

    firestore_misses = list(remaining_names)
    if not DEBUG_MODE and db and remaining_names:
        try:
            doc_refs = [db.collection("usda_cache").document(name.replace("/", "_")) for name in remaining_names]
            snapshots = db.get_all(doc_refs)
            for doc in snapshots:
                if doc.exists:
                    cached_food = doc.to_dict()
                    resolved[doc.id.replace("_", "/")] = cached_food
                    if doc.id in firestore_misses:
                        firestore_misses.remove(doc.id)
        except Exception:
            pass

    if firestore_misses:
        def query_worker(name):
            orig_text = name_to_original.get(name, name)
            try:
                return name, query_usda_api(name, conditions, orig_text, allergies, bypass_firestore=True)
            except Exception:
                return name, get_local_fallback(name)

        max_workers = min(len(firestore_misses), 8)
        with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = {executor.submit(query_worker, name): name for name in firestore_misses}
            for future in concurrent.futures.as_completed(futures):
                name, food_item = future.result()
                resolved[name] = food_item
                if not DEBUG_MODE:
                    USDA_CACHE[name] = food_item

        if not DEBUG_MODE and db:
            try:
                batch = db.batch()
                for name in firestore_misses:
                    if name in resolved:
                        doc_ref = db.collection("usda_cache").document(name.replace("/", "_"))
                        batch.set(doc_ref, strip_food_payload(resolved[name]))
                batch.commit()
            except Exception:
                pass
    return resolved

# ===================================================================================
# CLINICAL DEFENSIVE MACRO EXTRACTION & BATCH SUMMATION
# ===================================================================================
def patch_missing_commodity_macros(clean_name: str, extracted: dict) -> dict:
    name_lower = str(clean_name or "").lower()
    if any(term in name_lower for term in ["shrimp", "chicken", "beef", "pork", "fish", "salmon", "turkey", "tilapia", "cod", "sirloin"]):
        if extracted.get("protein_g", 0.0) == 0.0:
            extracted["protein_g"] = 22.0
    if any(term in name_lower for term in ["zucchini", "garlic", "parsley", "lemon", "lime"]):
        if extracted.get("potassium_mg", 0.0) == 0.0:
            if "garlic" in name_lower: extracted["potassium_mg"] = 401.0
            elif "parsley" in name_lower: extracted["potassium_mg"] = 554.0
            elif "zucchini" in name_lower: extracted["potassium_mg"] = 261.0
            elif "lemon" in name_lower or "lime" in name_lower: extracted["potassium_mg"] = 103.0
    if "oil" in name_lower or "butter" in name_lower:
        if extracted.get("calories", 0.0) == 0.0:
            extracted["calories"] = 884.0 if "oil" in name_lower else 717.0
    if "rice" in name_lower:
        if extracted.get("fiber_g", 0.0) == 0.0:
            extracted["fiber_g"] = 0.5
    return extracted

def extract_nutrients(food_item: dict, clean_name: str) -> dict:
    nutrients = food_item.get("foodNutrients", [])
    extracted = {k: 0.0 for k in NUTRIENT_MAPPING.keys()}
    for nutr in nutrients:
        nid = nutr.get("nutrientId")
        name = nutr.get("nutrientName", "").lower()
        unit = nutr.get("unitName", "").lower()
        val = float(nutr.get("value", 0.0))
        for key, config in NUTRIENT_MAPPING.items():
            match = False
            if nid in config["ids"]:
                match = True
            else:
                for kw in config["keywords"]:
                    if kw in name:
                        match = True
                        break
            if match and "unit_blacklist" in config:
                for bl in config["unit_blacklist"]:
                    if bl in unit:
                        match = False
                        break
            if match:
                extracted[key] = val
                break
    return patch_missing_commodity_macros(clean_name, extracted)

def calculate_batch_nutrition(parsed_ingredients: list, conditions: list, allergies: list, resolved_foods: dict = None) -> dict:
    totals = {k: 0.0 for k in NUTRIENT_MAPPING.keys()}
    for ing in (parsed_ingredients or []):
        if not ing:
            continue
        qty = parse_fraction_to_float(ing.get("quantity", 1.0))
        unit = ing.get("unit", "")
        clean_name = ing.get("clean_name", "")
        original_text = ing.get("original_text", "")
        weight_grams = unit_to_grams(qty, unit)
        if weight_grams <= 0.0:
            continue
        sanitized_name = sanitize_search_query(clean_name, original_text)
        actual_weight = adjust_weight_by_culinary_density(sanitized_name, unit, qty, weight_grams)
        food_item = resolved_foods.get(sanitized_name) if resolved_foods else query_usda_api(sanitized_name, conditions, original_text, allergies)
        if food_item:
            nutrients_per_100g = extract_nutrients(food_item, sanitized_name)
            is_leached = ing.get("leached", False) or "*" in str(clean_name) or "*" in str(original_text)
            for k, val in nutrients_per_100g.items():
                mult = 0.50 if (k == "potassium_mg" and is_leached) else 1.0
                totals[k] += (val * mult) * (actual_weight / 100.0)
    return totals

# ===================================================================================
# DETERMINISTIC CLINICAL RULES ENGINE & OPTIMIZER
# ===================================================================================
def evaluate_clinical_violations(parsed_ingredients: list, per_serving: dict, thresholds: dict, allergies: list) -> dict:
    violations = {
        "red_violations": {},
        "yellow_warnings": {},
        "allergy_violations": [],
        "overall_status": "Green"
    }
    for ft_key, limits in (thresholds or {}).items():
        backend_key = FRONTEND_NUTRIENT_MAP.get(ft_key)
        if not backend_key:
            continue
        current_val = per_serving.get(backend_key, 0.0)
        yellow_warning = limits.get("yellow_warning") if limits else None
        red_limit = limits.get("red_limit") if limits else None
        if red_limit is not None and current_val > red_limit:
            violations["red_violations"][backend_key] = {"limit": red_limit, "current": current_val, "frontend_key": ft_key}
            violations["overall_status"] = "Red"
        elif yellow_warning is not None and current_val > yellow_warning:
            violations["yellow_warnings"][backend_key] = {"limit": yellow_warning, "current": current_val, "frontend_key": ft_key}
            if violations["overall_status"] != "Red":
                violations["overall_status"] = "Yellow"

    for ing in (parsed_ingredients or []):
        if not ing:
            continue
        clean_name_lower = ing.get("clean_name", "").lower()
        original_text_lower = ing.get("original_text", "").lower()
        for allergy in (allergies or []):
            allergy_clean = str(allergy or "").lower().strip()
            if not allergy_clean:
                continue
            # BOTANICAL GUARD: Sweet potatoes are morning glories, not nightshade potatoes
            if allergy_clean in ["potato", "potatoes"] and ("sweet potato" in clean_name_lower or "sweet potato" in original_text_lower):
                continue
            if re.search(r'\b' + re.escape(allergy_clean) + r'\b', clean_name_lower) or re.search(r'\b' + re.escape(allergy_clean) + r'\b', original_text_lower):
                violations["allergy_violations"].append({"ingredient": ing.get("clean_name", ""), "allergy": allergy})
                violations["overall_status"] = "Red"
    return violations

def preprocess_clinical_swaps(parsed_ingredients: list, recipe_text: str, allergies: list, conditions: list, interventions: dict, thresholds: dict) -> list:
    """Deterministically applies clinical ingredient swaps based on profile flags, conditions, and active limits."""
    clean_conditions = [str(c or "").lower().strip() for c in (conditions or [])]
    is_aip = any(any(kw in cond for kw in ["aip", "autoimmune"]) for cond in clean_conditions)
    is_ckd = any(any(kw in cond for kw in ["ckd", "kidney", "renal", "dialysis"]) for cond in clean_conditions)
    is_celiac = any(any(kw in cond for kw in ["celiac", "gluten"]) for cond in clean_conditions)
    is_diabetes = any("diabetes" in cond for cond in clean_conditions)
    
    is_potassium_restricted = "potassium" in (thresholds or {}) or "potassium_mg" in (thresholds or {})
    is_ckd = is_ckd or is_potassium_restricted
    
    avoid_phosphate = interventions.get("avoid_phosphate_additives", False) or is_ckd or is_aip
    prefer_gf = interventions.get("prefer_preblended_gf_flour", False) or is_celiac
    limit_sat_fats = interventions.get("limit_saturated_fats", False) or is_diabetes or is_ckd

    swapped = []
    for ing in parsed_ingredients:
        if not ing:
            continue
        ing_copy = copy.deepcopy(ing)
        name_lower = ing_copy.get("clean_name", "").lower()
        qty = parse_fraction_to_float(ing_copy.get("quantity", 1.0))
        unit = ing_copy.get("unit", "")
        
        # 1. PHOSPHATE ELIMINATION: Baking Powder -> Baking Soda + Lemon Juice
        if avoid_phosphate and any(bp in name_lower for bp in ["baking powder", "double acting baking powder"]):
            soda_qty = round(qty * 0.25, 3)
            acid_qty = round(qty * 0.5, 3)
            soda_unit = "tsp" if "tsp" in unit or "spoon" in unit else unit
            acid_unit = "tsp" if "tsp" in unit or "spoon" in unit else unit
            
            swapped.append({
                "quantity": soda_qty,
                "unit": soda_unit,
                "clean_name": "baking soda",
                "original_text": f"{format_quantity_to_fraction(soda_qty)} {soda_unit} baking soda"
            })
            swapped.append({
                "quantity": acid_qty,
                "unit": acid_unit,
                "clean_name": "lemon juice",
                "original_text": f"{format_quantity_to_fraction(acid_qty)} {acid_unit} lemon juice (acid activator)"
            })
            continue

        # 2. AUTOIMMUNE PROTOCOL (AIP) DETERMINISTIC SWAPS (Takes priority over standard Celiac / Diabetes)
        if is_aip:
            if any(fw in name_lower for fw in ["all-purpose flour", "all purpose flour", "white flour", "wheat flour", "bread flour", "flour"]):
                ing_copy["clean_name"] = "cassava flour"
                ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} cassava flour (AIP grain-free)"
                swapped.append(ing_copy)
                continue
            if any(tw in name_lower for tw in ["tortilla", "tortillas", "taco shell", "taco shells"]):
                ing_copy["clean_name"] = "cassava tortilla"
                ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} cassava tortillas (AIP grain-free)"
                swapped.append(ing_copy)
                continue
            if any(pw in name_lower for pw in ["pizza crust", "flatbread"]):
                ing_copy["clean_name"] = "cassava pizza crust"
                ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} cassava pizza crust (AIP grain-free)"
                swapped.append(ing_copy)
                continue
            if any(tw in name_lower for tw in ["tomato sauce", "marinara", "crushed tomatoes", "diced tomatoes", "tomato paste", "marinara sauce", "pizza sauce"]):
                ing_copy["clean_name"] = "nomato sauce"
                ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} nomato sauce (beet, carrot & garlic puree)"
                swapped.append(ing_copy)
                continue
            if any(pw in name_lower for pw in ["potato", "potatoes"]) and "sweet potato" not in name_lower:
                ing_copy["clean_name"] = "sweet potatoes"
                ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} sweet potatoes (AIP nightshade-free)"
                swapped.append(ing_copy)
                continue
            if any(sw in name_lower for sw in ["granulated sugar", "white sugar", "brown sugar", "cane sugar", "sugar"]):
                if not any(f in name_lower for f in ["fruit", "berry", "cherry", "apple"]):
                    ing_copy["clean_name"] = "pure maple syrup"
                    ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} pure maple syrup (unrefined)"
                    swapped.append(ing_copy)
                    continue
            if any(fat_kw in name_lower for fat_kw in ["butter", "ghee", "margarine", "lard", "shortening"]):
                ing_copy["clean_name"] = "olive oil"
                ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} extra virgin olive oil"
                swapped.append(ing_copy)
                continue

        # 3. DISCRETE VESSEL SWAPS (Standard Non-AIP Flow)
        if is_diabetes and any(tw in name_lower for tw in ["flour tortilla", "flour tortillas", "wheat tortilla", "wheat tortillas"]):
            ing_copy["clean_name"] = "low-carb tortilla"
            ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} low-carb tortillas"
            swapped.append(ing_copy)
            continue
        if prefer_gf and any(tw in name_lower for tw in ["flour tortilla", "flour tortillas", "wheat tortilla", "wheat tortillas"]):
            ing_copy["clean_name"] = "white corn tortilla"
            ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} white corn tortillas (gluten-free)"
            swapped.append(ing_copy)
            continue
        if is_diabetes and any(pw in name_lower for pw in ["pizza crust", "flatbread"]):
            ing_copy["clean_name"] = "cauliflower pizza crust"
            ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} cauliflower pizza crust (low-carb)"
            swapped.append(ing_copy)
            continue
        if prefer_gf and any(pw in name_lower for pw in ["pizza crust", "flatbread"]):
            ing_copy["clean_name"] = "gluten-free pizza crust"
            ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} gluten-free pizza crust"
            swapped.append(ing_copy)
            continue

        # 4. FLOUR REPLACEMENT: Wheat / All-Purpose -> 1:1 Gluten-Free Blend
        if prefer_gf and any(fw in name_lower for fw in ["all-purpose flour", "all purpose flour", "white flour", "wheat flour", "bread flour"]):
            ing_copy["clean_name"] = "1:1 Gluten-Free Baking Flour Blend"
            ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} 1:1 Gluten-Free Baking Flour Blend"
            swapped.append(ing_copy)
            continue

        # 5. SUGAR REPLACEMENT: Granulated Sugar -> Erythritol Sweetener (Diabetes / Low-Carb)
        if is_diabetes and any(sw in name_lower for sw in ["granulated sugar", "white sugar", "brown sugar", "cane sugar"]):
            if not any(f in name_lower for f in ["fruit", "berry", "cherry", "apple"]):
                ing_copy["clean_name"] = "erythritol sweetener"
                ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} erythritol sweetener"
                swapped.append(ing_copy)
                continue

        # 6. SATURATED FAT LIMITATION: Heavy Butter / Lard -> Olive Oil
        if limit_sat_fats and any(fat_kw in name_lower for fat_kw in ["lard", "shortening", "palm oil"]):
            ing_copy["clean_name"] = "olive oil"
            ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} olive oil"
            swapped.append(ing_copy)
            continue

        # 7. POTASSIUM MANAGEMENT: Brown Rice -> White Rice (CKD / Renal)
        if is_ckd and "brown rice" in name_lower:
            ing_copy["clean_name"] = "cooked white rice" if "cooked" in name_lower else "white rice"
            ing_copy["original_text"] = f"{format_quantity_to_fraction(qty)} {unit} {ing_copy['clean_name']}"
            swapped.append(ing_copy)
            continue

        swapped.append(ing_copy)
    return swapped

def optimize_recipe_to_thresholds(original_parsed: list, servings: int, thresholds: dict, allergies: list, conditions: list, resolved_foods: dict, is_baking_or_dessert: bool = False) -> list:
    adjusted = copy.deepcopy(original_parsed)
    original_qtys = {idx: parse_fraction_to_float(ing.get("quantity", 0.0)) for idx, ing in enumerate(original_parsed)}
    
    PROTECTED_MICRO_KEYWORDS = [
        "vanilla", "extract", "cinnamon", "nutmeg", "clove", "pepper", "salt", "baking soda", "baking powder",
        "yeast", "oregano", "thyme", "basil", "rosemary", "dill", "parsley", "paprika", "cumin", "lemon juice",
        "lime juice", "vinegar", "cayenne", "coriander", "bay leaf"
    ]
    
    PROTECTED_VESSEL_KEYWORDS = [
        "tortilla", "tortillas", "taco shell", "taco shells", "bun", "buns", "pita", "pitas", 
        "wrapper", "wrappers", "crust", "pizza crust", "flatbread", "bread slice", "slices of bread"
    ]
    
    for iteration in range(20):
        # 1. Single-Application Allergy Stripping Guard (Prevents repeating prefixes)
        for ing in adjusted:
            clean_name_lower = ing.get("clean_name", "").lower()
            original_text_lower = ing.get("original_text", "").lower()
            for allergy in (allergies or []):
                allergy_clean = str(allergy or "").lower().strip()
                if not allergy_clean:
                    continue
                # BOTANICAL GUARD: Sweet potatoes are not nightshade potatoes
                if allergy_clean in ["potato", "potatoes"] and "sweet potato" in clean_name_lower:
                    continue
                if (allergy_clean in clean_name_lower or allergy_clean in original_text_lower) and not ing.get("allergy_removed", False):
                    ing["quantity"] = 0.0
                    ing["allergy_removed"] = True
                    ing["original_text"] = f"[ALLERGY REMOVED: {allergy.upper()}] " + ing.get("original_text", "")

        current_batch = calculate_batch_nutrition(adjusted, conditions, allergies, resolved_foods)
        per_serving = {k: val / servings for k, val in current_batch.items()}
        
        violations = []
        for ft_key, limits in (thresholds or {}).items():
            backend_key = FRONTEND_NUTRIENT_MAP.get(ft_key)
            if backend_key and limits and limits.get("red_limit") is not None:
                if per_serving.get(backend_key, 0.0) > limits["red_limit"]:
                    violations.append((backend_key, limits["red_limit"]))
        
        if not violations:
            break

        leaching_applied = False
        if not is_baking_or_dessert and "potassium_mg" in [v[0] for v in violations]:
            for ing in adjusted:
                clean_name_lower = ing.get("clean_name", "").lower()
                is_leachable = any(v in clean_name_lower for v in ["broccoli", "beans", "potato", "squash", "zucchini", "carrot", "greens", "spinach", "cabbage", "cauliflower"])
                if is_leachable and not ing.get("leached", False) and ing.get("quantity", 0.0) > 0.0:
                    ing["leached"] = True
                    leaching_applied = True
                    break
        if leaching_applied:
            continue

        worst_key, worst_limit = max(violations, key=lambda v: per_serving.get(v[0], 0.0) / (v[1] if v[1] != 0 else 0.001))
        highest_idx, highest_val = -1, -1.0
        for idx, ing in enumerate(adjusted):
            qty = parse_fraction_to_float(ing.get("quantity", 0.0))
            if qty <= 0.0 or ing.get("allergy_removed", False):
                continue
            clean_name_lower = ing.get("clean_name", "").lower()
            unit_lower = str(ing.get("unit", "")).lower()
            
            # Immunity Check: Spices, Micro-ingredients, and Discrete Vessels are immune from scaling
            if any(kw in clean_name_lower for kw in PROTECTED_MICRO_KEYWORDS) or any(vw in clean_name_lower for vw in PROTECTED_VESSEL_KEYWORDS) or unit_lower in ["tsp", "teaspoon", "pinch", "dash", "clove", "drop"]:
                continue
                
            min_factor = 0.95 if is_baking_or_dessert and any(x in clean_name_lower for x in ["flour", "starch", "blend"]) else 0.75
            if qty <= original_qtys.get(idx, qty) * min_factor:
                continue
            sanitized_name = sanitize_search_query(ing.get("clean_name", ""), ing.get("original_text", ""))
            food_item = resolved_foods.get(sanitized_name)
            if food_item:
                actual_weight = adjust_weight_by_culinary_density(sanitized_name, ing.get("unit", ""), qty, unit_to_grams(qty, ing.get("unit", "")))
                nutrients = extract_nutrients(food_item, sanitized_name)
                mult = 0.50 if (worst_key == "potassium_mg" and ing.get("leached", False)) else 1.0
                contrib = (nutrients.get(worst_key, 0.0) * mult) * (actual_weight / 100.0)
                if contrib > highest_val:
                    highest_val = contrib
                    highest_idx = idx

        if highest_idx != -1:
            target = adjusted[highest_idx]
            cur_qty = parse_fraction_to_float(target.get("quantity", 1.0))
            min_factor = 0.95 if is_baking_or_dessert and any(x in target.get("clean_name", "").lower() for x in ["flour", "starch", "blend"]) else 0.75
            min_allowed = original_qtys.get(highest_idx, cur_qty) * min_factor
            new_qty = max(round(cur_qty * 0.85, 3), min_allowed)
            target["quantity"] = new_qty
            target["original_text"] = f"{format_quantity_to_fraction(new_qty)} {target.get('unit', '')} {target.get('clean_name', '')}".strip()
            if target.get("leached", False):
                target["original_text"] += "*"
        else:
            break

    for ing in adjusted:
        qty = parse_fraction_to_float(ing.get("quantity", 0.0))
        if qty > 0.0 and not ing.get("allergy_removed", False):
            unit_str = ing.get("unit", "").strip()
            name_str = ing.get("clean_name", "").strip()
            ing["original_text"] = f"{format_quantity_to_fraction(qty)} {unit_str} {name_str}".strip()
            if ing.get("leached", False):
                ing["original_text"] += "*"
    return adjusted

# ===================================================================================
# GENERAL ASYNC GEMINI ROUTERS
# ===================================================================================
def sanitize_json_response(raw_text: str) -> str:
    if not raw_text:
        return "{}"
    trimmed = raw_text.strip()
    markdown_match = re.search(r"```(?:json)?\s*([\s\S]*?)\s*```", trimmed)
    extracted = markdown_match.group(1).strip() if markdown_match else trimmed
    first_brace = extracted.find('{')
    last_brace = extracted.rfind('}')
    if first_brace != -1 and last_brace != -1 and last_brace > first_brace:
        return extracted[first_brace:last_brace + 1]
    return extracted

async def call_gemini_with_retry_vertex_async(prompt: str, mime_type: str = "application/json") -> dict:
    generation_config = GenerationConfig(
        temperature=0.0,
        top_p=1.0,
        top_k=1,
        max_output_tokens=4096,
        response_mime_type=mime_type
    )
    for model_name in ACTIVE_MODELS:
        try:
            model = GenerativeModel(model_name)
            response = await asyncio.wait_for(model.generate_content_async(prompt, generation_config=generation_config), timeout=12.0)
            raw = response.text.strip()
            sanitized = sanitize_json_response(raw)
            return json.loads(sanitized)
        except Exception as e:
            logging.warning(f"[VERTEX AI RETRY] Model {model_name} failed: {e}")
            continue
    raise RuntimeError("Vertex AI exhausted")

def call_gemini_with_retry_rest(prompt: str, mime_type: str = "application/json", timeout_secs: float = 8.0) -> dict:
    headers = {"Content-Type": "application/json"}
    for model in ACTIVE_MODELS:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={GEMINI_API_KEY}"
        payload = {
            "contents": [{"parts": [{"text": prompt}]}],
            "generationConfig": {
                "temperature": 0.0,
                "topP": 1.0,
                "topK": 1,
                "maxOutputTokens": 4096,
                "responseMimeType": mime_type
            }
        }
        try:
            response = http_session.post(url, json=payload, headers=headers, timeout=(3, timeout_secs))
            if response.status_code == 200:
                candidates = response.json().get("candidates", [])
                if candidates:
                    raw = candidates[0]["content"]["parts"][0]["text"].strip()
                    sanitized = sanitize_json_response(raw)
                    return json.loads(sanitized)
        except Exception as e:
            logging.warning(f"[AI STUDIO REST RETRY] Model {model} failed: {e}")
            continue
    raise RuntimeError("REST AI Studio exhausted")

def call_gemini_with_retry(prompt: str, mime_type: str = "application/json", timeout_secs: float = 8.0) -> dict:
    try:
        return asyncio.run(asyncio.wait_for(call_gemini_with_retry_vertex_async(prompt, mime_type), timeout=timeout_secs))
    except Exception:
        pass
    return call_gemini_with_retry_rest(prompt, mime_type, timeout_secs)

def call_gemini_vision_with_retry(prompt: str, image_b64: str, mime_type: str = "application/json", image_mime_type: str = "image/jpeg", timeout_secs: float = 10.0) -> dict:
    headers = {"Content-Type": "application/json"}
    for model in ACTIVE_MODELS:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={GEMINI_API_KEY}"
        payload = {
            "contents": [{"parts": [{"text": prompt}, {"inlineData": {"mimeType": image_mime_type, "data": image_b64}}]}],
            "generationConfig": {
                "temperature": 0.0,
                "topP": 1.0,
                "topK": 1,
                "maxOutputTokens": 4096,
                "responseMimeType": mime_type
            }
        }
        try:
            response = http_session.post(url, json=payload, headers=headers, timeout=(3, timeout_secs))
            if response.status_code == 200:
                candidates = response.json().get("candidates", [])
                if candidates:
                    raw = candidates[0]["content"]["parts"][0]["text"].strip()
                    sanitized = sanitize_json_response(raw)
                    return json.loads(sanitized)
        except Exception as e:
            logging.warning(f"[VISION REST RETRY] Model {model} failed: {e}")
            continue
    raise RuntimeError("Vision API exhausted")

# ===================================================================================
# PASS 1: STRUCTURED NLP INGREDIENTS PARSER
# ===================================================================================
def parse_original_recipe_nlp(recipe_text: str) -> dict:
    prompt = f"""
You are an expert culinary parser. Extract the recipe title, serving count, and structured ingredients list:
{recipe_text}

CRITICAL PARSING RULES:
1. RECIPE TITLE: Extract the explicit recipe title into "recipe_name". If no title is given, generate a concise 2-4 word title.
2. SERVINGS: Extract explicit integer servings count. Default to 4 if not specified.
3. INGREDIENT LINES PARSING:
   - "quantity": decimal float (e.g. 0.5 for 1/2, 2.0 for 2).
   - "unit": standardized metric/US unit ("cup", "tsp", "tbsp", "oz", "lb", "g", "clove", "dash", "pinch", "slice", "sprig", "unit").
   - "clean_name": base commodity name without descriptors ("diced", "chopped", "peeled").
   - "original_text": the exact ingredient line.
4. SPICE, GARNISH, & OPTION RULES (MANDATORY):
   - A 'dash', 'pinch', or 'dash(es)' of any spice (like cayenne pepper, cinnamon, nutmeg, salt, black pepper) is 0.5 grams of spice, NEVER whole units or pieces of fresh produce. Always set unit to 'dash' or 'pinch'.
   - A 'clove' of garlic is 3 grams. Always set unit to 'clove' and clean_name to 'garlic'.
   - If an ingredient line offers choices with 'or' (e.g. 'Cotija cheese or queso fresco', 'cod or tilapia'), default the math to Option A (the first option) as clean_name.
   - If an item has an unmeasured garnish (e.g. 'to taste', 'lime wedges to serve'), estimate a standard trace or 1 oz portion (e.g. quantity 1.0, unit 'tbsp' or 'unit').
5. STRICT NOISE FILTERING: Discard cooking steps, prep times, keyboard layout labels, and website navigation text.

Return raw JSON matching this schema exactly:
{{
  "recipe_name": "Broccoli and Beef Stir-Fry",
  "servings": 4,
  "original_parsed": [
    {{
      "quantity": 6.0,
      "unit": "oz",
      "clean_name": "sirloin beef",
      "original_text": "6 oz sirloin beef"
    }}
  ]
}}
"""
    try:
        return call_gemini_with_retry(prompt, mime_type="application/json", timeout_secs=12.0)
    except Exception as e:
        logging.error(f"[GEMINI NLP PARSE ERROR] Trace: {e}")
        return {"recipe_name": "Analyzed Recipe", "servings": 4, "original_parsed": []}

# ===================================================================================
# GCP HTTP CLOUD RUN HANDLER
# ===================================================================================
@functions_framework.http
def analyzeRecipe(request):
    if request.method == "OPTIONS":
        headers = {
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Methods": "POST, OPTIONS",
            "Access-Control-Allow-Headers": "Content-Type, Authorization",
            "Access-Control-Max-Age": "3600"
        }
        return ("", 204, headers)

    headers = {"Access-Control-Allow-Origin": "*"}
    
    # BETA PHASE ACCESS: Log verified UID if sent, but do not block beta testers with 401
    user_uid = get_authenticated_uid(request)
    if user_uid:
        logging.info(f"[BETA SESSION] Authenticated UID: {user_uid}")
    else:
        logging.info("[BETA SESSION] Guest tester invocation allowed without token lock.")

    try:
        request_json = request.get_json(silent=True) or {}
        action = request_json.get("action") or "analyze"

        # ---------------------------------------------------------------------------
        # ROUTE: Scan OCR Text (scan_text)
        # ---------------------------------------------------------------------------
        if action == "scan_text":
            extracted_text = request_json.get("extracted_ocr_text") or ""
            prompt = """Translate OCR label text into flat JSON:
{
  "nutrition_facts_found": true,
  "servings": 1.0,
  "calories": 0.0,
  "sodium": 0.0,
  "protein": 0.0,
  "carbs": 0.0,
  "fiber": 0.0,
  "sugar": 0.0,
  "added_sugar": 0.0,
  "total_fat": 0.0,
  "sat_fat": 0.0,
  "potassium": 0.0,
  "di": []
}
Text:
""" + extracted_text
            try:
                result = call_gemini_with_retry(prompt, timeout_secs=8.0)
            except Exception:
                result = {"nutrition_facts_found": False, "servings": 1.0, "di": []}
            return (json.dumps(result), 200, headers)

# ---------------------------------------------------------------------------
        # ROUTE: Scan Image (scan_image)
        # ---------------------------------------------------------------------------
        elif action == "scan_image":
            image_b64 = request_json.get("image_b64") or ""
            prompt = """Analyze the image. If this is NOT a food product, food package, or nutrition facts label (e.g. it is a coffee mug, object, landscape, animal, or non-food item), set "is_food": false and "product_name": "Non-food item detected".
If it IS a valid food product or nutrition facts label, set "is_food": true and extract the details into this exact JSON format:
{
  "is_food": true,
  "product_name": "Product Name",
  "nutrition_facts_found": true,
  "servings": 1.0,
  "calories": 0.0,
  "sodium": 0.0,
  "protein": 0.0,
  "carbs": 0.0,
  "fiber": 0.0,
  "sugar": 0.0,
  "total_fat": 0.0,
  "sat_fat": 0.0,
  "potassium": 0.0,
  "di": []
}"""
            try:
                result = call_gemini_vision_with_retry(prompt, image_b64, timeout_secs=10.0)
            except Exception:
                result = {"is_food": False, "product_name": "Non-food item detected", "nutrition_facts_found": False, "servings": 1.0, "di": []}
            return (json.dumps(result), 200, headers)
            
        # ---------------------------------------------------------------------------
        # ROUTE: Scan Produce (scan_produce)
        # ---------------------------------------------------------------------------
        elif action == "scan_produce":
            image_b64 = request_json.get("image_b64") or ""
            prompt = """Identify raw produce item and return USDA values per 100g in JSON:
{
  "item_name": "Name",
  "servings_per_container": 1.0,
  "calories": 0.0,
  "sodium_mg": 0.0,
  "protein_g": 0.0,
  "total_carbohydrates_g": 0.0,
  "total_sugar_g": 0.0,
  "total_fat_g": 0.0,
  "saturated_fat_g": 0.0,
  "fiber": 0.0,
  "potassium_mg": 0.0,
  "di": ["RAW PRODUCE"]
}"""
            try:
                result = call_gemini_vision_with_retry(prompt, image_b64, timeout_secs=10.0)
            except Exception:
                result = {"item_name": "Produce", "servings_per_container": 1.0, "di": ["RAW PRODUCE"]}
            return (json.dumps(result), 200, headers)

        # ---------------------------------------------------------------------------
        # ROUTE: On-Demand Steps Synthesis (steps)
        # ---------------------------------------------------------------------------
        elif action == "steps":
            adjusted_ingredients = request_json.get("adjusted_ingredients") or ""
            prompt = f"""You are an expert chef. Output clear, numbered preparation instructions for this adjusted ingredients list:
{adjusted_ingredients}

JSON format:
{{
  "instructions": "1. Step one...\\n2. Step two..."
}}"""
            try:
                result = call_gemini_with_retry(prompt, timeout_secs=10.0)
            except Exception:
                result = {"instructions": "1. Prepare adjusted ingredients according to standard culinary directions."}
            return (json.dumps(result), 200, headers)

        # ---------------------------------------------------------------------------
        # ROUTE: On-Demand Clinical Rationale Synthesis (rationale)
        # ---------------------------------------------------------------------------
        elif action == "rationale":
            original = request_json.get("original_ingredients") or ""
            adjusted = request_json.get("adjusted_ingredients") or ""
            conditions = request_json.get("conditions") or []
            prompt = f"""You are a clinical dietitian. Explain why substitutions were made for conditions {conditions}:
Original:
{original}
Adjusted:
{adjusted}

JSON format:
{{
  "rationale": "- Substituted standard flour with grain-free cassava flour...\\n- Replaced tomato sauce with nightshade-free nomato sauce..."
}}"""
            try:
                result = call_gemini_with_retry(prompt, timeout_secs=10.0)
            except Exception:
                result = {"rationale": "Ingredients were adjusted to satisfy profile safety limits."}
            return (json.dumps(result), 200, headers)

        # ---------------------------------------------------------------------------
        # ROUTE: Single-Pass Deterministic Recipe Analysis (analyze)
        # ---------------------------------------------------------------------------
        elif action == "analyze":
            recipe_text = request_json.get("recipe_text") or request_json.get("recipeText") or ""
            if not recipe_text:
                return (json.dumps({"error": "Missing recipe payload."}), 400, headers)

            conditions = request_json.get("conditions") or []
            allergies = request_json.get("allergies") or []
            thresholds = request_json.get("thresholds") or {}
            interventions = request_json.get("interventions") or {}

            # 1. Single LLM Call: Parse Raw Input to Structured JSON
            nlp_result = parse_original_recipe_nlp(recipe_text)
            original_parsed = nlp_result.get("original_parsed") or []
            recipe_name = nlp_result.get("recipe_name") or "Adjusted Recipe"
            
            client_servings = request_json.get("servings")
            servings = int(client_servings) if client_servings else int(nlp_result.get("servings", 4))
            servings = max(servings, 1)

            text_lower = recipe_text.lower()
            is_baking_or_dessert = any(kw in text_lower for kw in [
                "cobbler", "cake", "cookie", "pie", "muffin", "brownie", "dough", "dessert", "bake", "baking", "pastry"
            ])

            # 2. Deterministic Clinical Preprocessing
            original_parsed_swapped = preprocess_clinical_swaps(
                original_parsed, recipe_text, allergies, conditions, interventions, thresholds
            )

            # 3. High-Speed Macro Prefetch
            resolved_foods = prefetch_resolved_foods(original_parsed_swapped, conditions, allergies)

            # 4. Calculate Original Nutritional Batch Sum
            original_batch_nutrition = calculate_batch_nutrition(original_parsed, conditions, allergies, resolved_foods)
            per_serving_orig = {k: val / servings for k, val in original_batch_nutrition.items()}

            # 5. Evaluate Clinical Violations
            violations = evaluate_clinical_violations(original_parsed_swapped, per_serving_orig, thresholds, allergies)

            # 6. In-Memory Deterministic Optimization
            has_violations = (violations["overall_status"] == "Red")
            if has_violations:
                adjusted_parsed = optimize_recipe_to_thresholds(
                    original_parsed_swapped, servings, thresholds, allergies, conditions, resolved_foods, is_baking_or_dessert
                )
            else:
                adjusted_parsed = original_parsed_swapped

            # 7. Construct Final Ingredients Text (Filtering out removed allergens and 0-qty items)
            has_leached = any(ing.get("leached", False) for ing in adjusted_parsed)
            ingredient_lines = []
            for ing in adjusted_parsed:
                qty = parse_fraction_to_float(ing.get("quantity", 0.0))
                # Only include active, non-zero ingredients that were not completely removed as allergens
                if qty > 0.0 and not ing.get("allergy_removed", False):
                    orig_text = ing.get("original_text", "").strip()
                    if orig_text:
                        ingredient_lines.append(f"- {orig_text}")
            
            if has_leached and not is_baking_or_dessert:
                footnote = "\n\n*Leaching Process: Slice vegetables thin, rinse in warm water, soak for at least 2 hours in warm water, rinse again, and boil in fresh water to leach out 50-75% of potassium."
                adjusted_ingredients_text = "\n".join(ingredient_lines) + footnote
            else:
                adjusted_ingredients_text = "\n".join(ingredient_lines)

            # 8. Calculate Adjusted Nutrition
            adjusted_resolved_foods = prefetch_resolved_foods(adjusted_parsed, conditions, allergies)
            resolved_foods.update(adjusted_resolved_foods)
            adjusted_batch_nutrition = calculate_batch_nutrition(adjusted_parsed, conditions, allergies, resolved_foods)

            output_payload = {
                "recipe_name": recipe_name,
                "status": violations["overall_status"],
                "servings": servings,
                "original_nutrition": original_batch_nutrition,
                "adjusted_nutrition": adjusted_batch_nutrition,
                "adjusted_ingredients": adjusted_ingredients_text,
                "violations": violations
            }
            return (json.dumps(output_payload), 200, headers)

        else:
            return (json.dumps({"error": f"Invalid action: {action}"}), 400, headers)

    except Exception as e:
        logging.exception("Cloud Run pipeline crash:")
        return (json.dumps({"error": f"Internal server error: {str(e)}"}), 500, headers)