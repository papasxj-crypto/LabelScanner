package net.meinook.labelscanner

data class EvaluationResult(
    val bgColor: Int,
    val textColor: Int,
    val subtextColor: Int,
    val gradeTitle: String,
    val redViolations: List<String>,
    val yellowViolations: List<String>
) : java.io.Serializable

data class ProductAlternative(
    val name: String,
    val brand: String,
    val code: String,
    val gradeTitle: String
) : java.io.Serializable
