package coil.request

import android.view.View
import coil.ImageLoader
import coil.annotation.ExperimentalCoil
import coil.target.ViewTarget
import coil.util.requestManager
import kotlinx.coroutines.Job

/**
 * Represents the work of an [ImageLoader.load] request.
 */
interface RequestDisposable {

    /**
     * Return true if the request is complete or cancelling.
     */
    val isDisposed: Boolean

    /**
     * Cancel any in progress work and free any resources associated with this request. This method is idempotent.
     */
    fun dispose()

    /**
     * Suspend until any in progress work completes.
     */
    @ExperimentalCoil
    suspend fun await()
}

/** Used for a one-shot image request. */
internal class BaseTargetRequestDisposable(private val job: Job) : RequestDisposable {

    override val isDisposed
        get() = !job.isActive

    override fun dispose() {
        if (!isDisposed) {
            job.cancel()
        }
    }

    @ExperimentalCoil
    override suspend fun await() = job.join()
}

/**
 * Used for requests that are attached to a [View].
 *
 * Requests attached to a view are automatically restarted when
 * [View.OnAttachStateChangeListener.onViewAttachedToWindow] is called.
 * As a result, [isDisposed] will return true until [dispose] is called
 * or another request has been attached to the view.
 */
internal class ViewTargetRequestDisposable(
    private val target: ViewTarget<*>,
    private val request: LoadRequest
) : RequestDisposable {

    /** TODO: This isn't a perfect check since we can reuse the same [LoadRequest] for multiple distinct requests. */
    override var isDisposed = false
        get() = field || target.view.requestManager.getCurrentRequest()?.request !== request
        private set

    override fun dispose() {
        if (!isDisposed) {
            isDisposed = true
            target.view.requestManager.clearCurrentRequest()
        }
    }

    @ExperimentalCoil
    override suspend fun await() {
        if (!isDisposed) {
            target.view.requestManager.getCurrentRequest()?.job?.join()
        }
    }
}
