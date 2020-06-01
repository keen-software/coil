package coil.memory

import android.graphics.Bitmap
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import coil.EventListener
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.request.GetRequest
import coil.request.LoadRequest
import coil.request.Request
import coil.target.PoolableViewTarget
import coil.target.Target
import coil.target.ViewTarget
import coil.util.Logger
import coil.util.requestManager
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * [DelegateService] wraps [Target]s to support [Bitmap] pooling and [Request]s to manage their lifecycle.
 */
@OptIn(ExperimentalCoilApi::class)
internal class DelegateService(
    private val imageLoader: ImageLoader,
    private val referenceCounter: BitmapReferenceCounter,
    private val logger: Logger?
) {

    /** Wrap the [request]'s [Target] to support [Bitmap] pooling. */
    fun createTargetDelegate(
        request: Request,
        eventListener: EventListener
    ): TargetDelegate {
        return when (request) {
            is GetRequest -> InvalidatableEmptyTargetDelegate(referenceCounter)
            is LoadRequest -> when (val target = request.target) {
                null -> EmptyTargetDelegate
                is PoolableViewTarget<*> -> PoolableTargetDelegate(request, target, referenceCounter, eventListener, logger)
                else -> InvalidatableTargetDelegate(request, target, referenceCounter, eventListener, logger)
            }
        }
    }

    /** Wrap [request] to automatically dispose (and for [ViewTarget]s restart) the [Request] based on its lifecycle. */
    @MainThread
    fun createRequestDelegate(
        coroutineContext: CoroutineContext,
        request: Request,
        targetDelegate: TargetDelegate,
        lifecycle: Lifecycle
    ): RequestDelegate {
        val requestDelegate: RequestDelegate

        when (request) {
            is GetRequest -> {
                requestDelegate = EmptyRequestDelegate
                lifecycle.addObserver(requestDelegate)
            }
            is LoadRequest -> when (val target = request.target) {
                is ViewTarget<*> -> {
                    requestDelegate = ViewTargetRequestDelegate(
                        imageLoader = imageLoader,
                        request = request,
                        target = targetDelegate,
                        lifecycle = lifecycle,
                        job = coroutineContext[Job]!!
                    )
                    lifecycle.addObserver(requestDelegate)

                    // Attach this request to the target's view.
                    target.view.requestManager.setCurrentRequest(requestDelegate)
                }
                else -> {
                    requestDelegate = BaseRequestDelegate(lifecycle, coroutineContext[Job]!!)
                    lifecycle.addObserver(requestDelegate)
                }
            }
        }

        return requestDelegate
    }
}
