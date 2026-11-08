package app.finance

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SingleInstanceTest {

    @Test
    fun `второй экземпляр не запускается и просит первый показать окно`(@TempDir dir: Path) {
        val first = assertNotNull(SingleInstance.acquire(dir))
        first.use {
            val activated = CountDownLatch(1)
            first.onActivate { activated.countDown() }

            var delivered = false
            val second = SingleInstance.acquire(dir) { delivered = it }

            assertNull(second)
            assertTrue(delivered, "просьба показать окно не дошла")
            assertTrue(activated.await(5, TimeUnit.SECONDS), "первый экземпляр не получил просьбу")
        }
    }

    @Test
    fun `после закрытия первого каталог снова свободен`(@TempDir dir: Path) {
        assertNotNull(SingleInstance.acquire(dir)).close()
        assertNotNull(SingleInstance.acquire(dir)).close()
    }
}
