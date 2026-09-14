package nl.baskt.ui.search

/** ML Kit reports upright-image coordinates; CameraX's sensor matrix uses the raw buffer. */
internal fun barcodePointInBuffer(x: Float, y: Float, width: Int, height: Int, rotation: Int): Pair<Float, Float> =
    when (rotation) {
        0 -> x to y
        90 -> y to height - x
        180 -> width - x to height - y
        270 -> width - y to x
        else -> error("Unsupported camera rotation: $rotation")
    }
