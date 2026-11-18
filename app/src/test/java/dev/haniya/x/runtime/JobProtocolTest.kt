package dev.haniya.x.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobProtocolTest {
    @Test
    fun `parses protocol lines`() {
        assertEquals(JobLine.Step("下载 Codex"), JobProtocol.parse("::step:: 下载 Codex"))
        assertEquals(JobLine.Progress(0.42f), JobProtocol.parse("::progress:: 42"))
        assertEquals(JobLine.Progress(0.5f), JobProtocol.parse("::progress:: 50%"))
        assertEquals(JobLine.Progress(0.125f), JobProtocol.parse("::progress::12.5"))
        assertEquals(JobLine.Error("网络不可用"), JobProtocol.parse("::error:: 网络不可用"))
        assertEquals(JobLine.Result("1.2.3"), JobProtocol.parse("::result:: 1.2.3"))
        assertEquals(JobLine.Result(""), JobProtocol.parse("::result::"))
    }

    @Test
    fun `leading whitespace before a marker is tolerated`() {
        assertEquals(JobLine.Step("x"), JobProtocol.parse("   ::step:: x"))
    }

    @Test
    fun `progress is clamped`() {
        assertEquals(JobLine.Progress(1f), JobProtocol.parse("::progress:: 250"))
        assertEquals(JobLine.Progress(0f), JobProtocol.parse("::progress:: -3"))
    }

    @Test
    fun `malformed markers fall back to log lines`() {
        assertEquals(JobLine.Log("::progress:: lots"), JobProtocol.parse("::progress:: lots"))
        assertEquals(JobLine.Log("::progress:: NaN"), JobProtocol.parse("::progress:: NaN"))
        assertEquals(JobLine.Log("::step::"), JobProtocol.parse("::step::"))
        assertEquals(JobLine.Log("::error::   "), JobProtocol.parse("::error::   "))
        assertEquals(JobLine.Log("::other:: x"), JobProtocol.parse("::other:: x"))
        assertEquals(JobLine.Log("plain output"), JobProtocol.parse("plain output"))
        assertEquals(JobLine.Log("a ::step:: inside"), JobProtocol.parse("a ::step:: inside"))
    }

    @Test
    fun `accumulator tracks steps and marks the previous one done`() {
        val acc = JobAccumulator()
        assertTrue(acc.apply("::step:: 一"))
        assertTrue(acc.apply("::progress:: 30"))
        assertEquals(0.3f, acc.progress!!, 1e-6f)
        assertTrue(acc.apply("::step:: 二"))
        assertNull("a new step resets progress", acc.progress)
        val snap = acc.snapshot("j", "t", JobState.Running, null)
        assertEquals(listOf(JobStep("一", done = true), JobStep("二", done = false)), snap.steps)
    }

    @Test
    fun `repeating the current step is ignored`() {
        val acc = JobAccumulator()
        acc.apply("::step:: 一")
        assertFalse(acc.apply("::step:: 一"))
        assertEquals(1, acc.snapshot("j", "t", JobState.Running, null).steps.size)
    }

    @Test
    fun `log lines are not important, errors are logged and kept`() {
        val acc = JobAccumulator()
        assertFalse(acc.apply("hello"))
        assertTrue(acc.apply("::error:: 存储空间不足"))
        assertTrue(acc.apply("::result:: 2.0.1"))
        val s = acc.snapshot("j", "t", JobState.Running, null)
        assertEquals(listOf("hello", "存储空间不足"), s.log)
        assertEquals("存储空间不足", s.error)
        assertEquals("2.0.1", s.result)
    }

    @Test
    fun `log keeps only the last lines`() {
        val acc = JobAccumulator(maxLog = 500)
        repeat(1200) { acc.apply("line $it") }
        val log = acc.snapshot("j", "t", JobState.Running, null).log
        assertEquals(500, log.size)
        assertEquals("line 700", log.first())
        assertEquals("line 1199", log.last())
    }

    @Test
    fun `success completes all steps and progress`() {
        val acc = JobAccumulator()
        acc.apply("::step:: a"); acc.apply("::step:: b"); acc.apply("::progress:: 80")
        assertEquals(JobState.Succeeded, acc.finish(0, cancelled = false))
        val s = acc.snapshot("j", "t", JobState.Succeeded, 0)
        assertTrue(s.steps.all { it.done && !it.failed })
        assertEquals(1f, s.progress!!, 0f)
        assertNull(s.error)
    }

    @Test
    fun `failure marks the running step and gives a default error`() {
        val acc = JobAccumulator()
        acc.apply("::step:: a"); acc.apply("::step:: b")
        assertEquals(JobState.Failed, acc.finish(3, cancelled = false))
        val s = acc.snapshot("j", "t", JobState.Failed, 3)
        assertEquals(JobStep("a", done = true), s.steps[0])
        assertEquals(JobStep("b", done = false, failed = true), s.steps[1])
        assertEquals("执行失败（退出码 3）", s.error)
    }

    @Test
    fun `script error wins over the default message`() {
        val acc = JobAccumulator()
        acc.apply("::error:: 找不到 npm")
        acc.finish(1, cancelled = false)
        assertEquals("找不到 npm", acc.error)
    }

    @Test
    fun `launch failure is a failure even without exit code`() {
        val acc = JobAccumulator()
        assertEquals(JobState.Failed, acc.finish(null, cancelled = false, failure = "工作区尚未创建"))
        assertEquals("工作区尚未创建", acc.error)
    }

    @Test
    fun `cancel wins over the exit code`() {
        val acc = JobAccumulator()
        acc.apply("::step:: a")
        assertEquals(JobState.Cancelled, acc.finish(143, cancelled = true))
        assertNull(acc.error)
        assertFalse(acc.snapshot("j", "t", JobState.Cancelled, 143).steps[0].failed)
    }

    @Test
    fun `default errors describe common exit codes`() {
        assertEquals("执行超时", JobAccumulator.defaultError(124))
        assertEquals("找不到命令（退出码 127）", JobAccumulator.defaultError(127))
        assertEquals("进程被系统中止（退出码 137）", JobAccumulator.defaultError(137))
        assertEquals("执行失败", JobAccumulator.defaultError(null))
    }
}
