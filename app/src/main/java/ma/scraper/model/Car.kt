package ma.scraper.model

data class Car(
    val id: String,
    val url: String,
    val title: String,
    val fuel: String? = null,
    val transmission: String? = null,
    val picker: String? = null,

    val year: Int? = null,
    val km: Int? = null,
    val ps: Int? = null,
    val kw: Int? = null,

    val sellerType: String? = null,
    val location: String? = null,
    val priceEur: Int? = null,

    val image: String? = null
)
