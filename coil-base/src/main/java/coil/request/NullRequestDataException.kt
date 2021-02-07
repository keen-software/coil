package coil.request

import coil.ImageLoader

/**
 * Exception thrown when an [ImageRequest] with empty/null data is executed by an [ImageLoader].
 */
public class NullRequestDataException : RuntimeException("The request's data is null.")
