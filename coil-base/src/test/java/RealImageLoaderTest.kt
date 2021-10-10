package coil

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.request.ImageRequest
import coil.util.createMockWebServer
import coil.util.createTestMainDispatcher
import coil.util.runBlockingTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class RealImageLoaderTest {

    private lateinit var context: Context
    private lateinit var mainDispatcher: TestCoroutineDispatcher
    private lateinit var server: MockWebServer
    private lateinit var imageLoader: ImageLoader

    @Before
    fun before() {
        context = ApplicationProvider.getApplicationContext()
        mainDispatcher = createTestMainDispatcher()
        server = createMockWebServer()
        imageLoader = ImageLoader.Builder(context)
            .diskCache(null)
            .build()
    }

    @After
    fun after() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    /** Regression test: https://github.com/coil-kt/coil/issues/933 */
    @Test
    fun executeIsCancelledIfScopeIsCancelled() = runBlockingTest {
        val isCancelled = MutableStateFlow(false)

        val scope = CoroutineScope(mainDispatcher)
        scope.launch {
            val request = ImageRequest.Builder(context)
                .data(server.url("normal.jpg"))
                .listener(onCancel = {
                    isCancelled.value = true
                })
                .build()
            imageLoader.execute(request)
        }

        assertTrue(scope.isActive)
        assertFalse(isCancelled.value)

        scope.cancel()

        withTimeout(5_000) {
            isCancelled.first { it }
        }
    }
}
