@file:JvmName("DeprecatedSingletonKt")

package coil.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest

@Deprecated(
    message = "ImagePainter has been renamed to AsyncImagePainter.",
    replaceWith = ReplaceWith(
        expression = "rememberAsyncImagePainter(" +
            "ImageRequest.Builder(LocalContext.current).data(data).apply(builder).build())",
        imports = ["coil.compose.rememberAsyncImagePainter", "coil.request.ImageRequest"]
    )
)
@Composable
inline fun rememberImagePainter(
    data: Any?,
    builder: ImageRequest.Builder.() -> Unit = {},
) = rememberAsyncImagePainter(
    model = ImageRequest.Builder(LocalContext.current)
        .data(data)
        .apply(builder)
        .build()
)

@Deprecated(
    message = "ImagePainter has been renamed to AsyncImagePainter.",
    replaceWith = ReplaceWith(
        expression = "rememberAsyncImagePainter(request)",
        imports = ["coil.compose.rememberAsyncImagePainter"]
    )
)
@Composable
fun rememberImagePainter(request: ImageRequest) = rememberAsyncImagePainter(request)
