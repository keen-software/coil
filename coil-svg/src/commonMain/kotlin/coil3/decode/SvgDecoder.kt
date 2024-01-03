package coil3.decode

import coil3.request.Options

/**
 * A [Decoder] that uses [AndroidSVG](https://bigbadaboom.github.io/androidsvg/)
 * and [SVGDOM](https://api.skia.org/classSkSVGDOM.html/) to decode SVG
 * files.
 *
 * @param useViewBoundsAsIntrinsicSize If true, uses the SVG's view bounds as the intrinsic size for
 *  the SVG. If false, uses the SVG's width/height as the intrinsic size for the SVG.
 */
expect class SvgDecoder(
    source: ImageSource,
    options: Options,
    useViewBoundsAsIntrinsicSize: Boolean = true,
) : Decoder {

    class Factory(
        useViewBoundsAsIntrinsicSize: Boolean = true,
    ) : Decoder.Factory
}

internal const val MIME_TYPE_SVG = "image/svg+xml"
internal const val DEFAULT_SIZE = 512f
