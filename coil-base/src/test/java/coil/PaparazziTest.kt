package coil

import app.cash.paparazzi.DeviceConfig.Companion.PIXEL_6
import app.cash.paparazzi.Paparazzi
import org.junit.After
import org.junit.Rule
import org.junit.Test

class PaparazziTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = PIXEL_6,
        theme = "android:Theme.Material.Light.NoActionBar",
    )
    val imageLoader = ImageLoader(paparazzi.context)

    @After
    fun after() {
        imageLoader.shutdown()
    }

    @Test
    fun loadView() {
    }
}
