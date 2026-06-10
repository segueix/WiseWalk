package com.wisewalk.app

import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * CARTO basemaps: minimalist, mostly-gray cartography in the style of
 * Waze/Pokémon GO. Free to use with OpenStreetMap + CARTO attribution.
 * The light variant backs the light theme and the dark variant the dark one.
 */
object CartoTileSources {

    private const val COPYRIGHT = "© OpenStreetMap contributors © CARTO"

    val LIGHT = XYTileSource(
        "CartoPositron", 0, 20, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/light_all/",
            "https://b.basemaps.cartocdn.com/light_all/",
            "https://c.basemaps.cartocdn.com/light_all/",
            "https://d.basemaps.cartocdn.com/light_all/"
        ),
        COPYRIGHT
    )

    val DARK = XYTileSource(
        "CartoDarkMatter", 0, 20, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/dark_all/",
            "https://b.basemaps.cartocdn.com/dark_all/",
            "https://c.basemaps.cartocdn.com/dark_all/",
            "https://d.basemaps.cartocdn.com/dark_all/"
        ),
        COPYRIGHT
    )
}
