package ma.scraper.model

data class Car(
    val id: String,
    val url: String,
    val title: String,
    val priceEur: Int? = null,
    val location: String? = null,
    val image: String? = null
)
