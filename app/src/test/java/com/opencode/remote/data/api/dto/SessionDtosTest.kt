package com.opencode.remote.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for session-related DTO deserialization.
 *
 * Pure JVM tests — no Android dependency required.
 * Uses the same Json configuration as the app: ignoreUnknownKeys = true, coerceInputValues = true.
 */
class SessionDtosTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ─── SessionTime.archived ────────────────────────────────────────────

    @Test
    fun `sessionTime without archived deserializes as null`() {
        val input = """{
            "created": 1000,
            "updated": 2000
        }"""
        val time = json.decodeFromString<SessionTime>(input)
        assertEquals(1000L, time.created)
        assertEquals(2000L, time.updated)
        assertNull(time.archived)
    }

    @Test
    fun `sessionTime with time archived deserializes correctly`() {
        val input = """{
            "created": 1000,
            "updated": 2000,
            "time.archived": 3000
        }"""
        val time = json.decodeFromString<SessionTime>(input)
        assertEquals(1000L, time.created)
        assertEquals(2000L, time.updated)
        assertEquals(3000L, time.archived)
    }

    @Test
    fun `sessionTime with only archived present deserializes`() {
        val input = """{
            "time.archived": 9999
        }"""
        val time = json.decodeFromString<SessionTime>(input)
        assertEquals(9999L, time.archived)
        assertNull(time.created)
        assertNull(time.updated)
    }

    // ─── SessionPermission ──────────────────────────────────────────────

    @Test
    fun `sessionPermission without pattern and permission defaults to null`() {
        val input = """{
            "action": "read"
        }"""
        val perm = json.decodeFromString<SessionPermission>(input)
        assertEquals("read", perm.action)
        assertNull(perm.permission)
        assertNull(perm.pattern)
    }

    @Test
    fun `sessionPermission with all fields deserializes correctly`() {
        val input = """{
            "permission": "allow",
            "action": "write",
            "pattern": "src/**"
        }"""
        val perm = json.decodeFromString<SessionPermission>(input)
        assertEquals("allow", perm.permission)
        assertEquals("write", perm.action)
        assertEquals("src/**", perm.pattern)
    }

    @Test
    fun `sessionPermission with partial fields deserializes`() {
        val input = """{
            "permission": "deny"
        }"""
        val perm = json.decodeFromString<SessionPermission>(input)
        assertEquals("deny", perm.permission)
        assertEquals("", perm.action)
        assertNull(perm.pattern)
    }

    // ─── SessionInfo backward compat (integration) ────────────────────

    @Test
    fun `sessionInfo without new fields deserializes as backward compat`() {
        val input = """{
            "id": "ses_001",
            "slug": "my-session",
            "title": "Test Session"
        }"""
        val info = json.decodeFromString<SessionInfo>(input)
        assertEquals("ses_001", info.id)
        assertEquals("my-session", info.slug)
        assertNull(info.time) // no time object at all
        assertNull(info.revert)
        assertNull(info.permission)
    }

    @Test
    fun `sessionInfo with all new fields deserializes correctly`() {
        val input = """{
            "id": "ses_002",
            "title": "Full Session",
            "permission": [
                {"permission": "allow", "action": "read", "pattern": "src/**"}
            ],
            "time": {
                "created": 100,
                "updated": 200,
                "time.archived": 300
            },
            "revert": {
                "messageID": "msg_001",
                "partID": "part_002",
                "snapshot": "snap_v1",
                "diff": "diff_content"
            }
        }"""
        val info = json.decodeFromString<SessionInfo>(input)
        assertEquals("ses_002", info.id)

        // permission
        val perms = info.permission
        assertNotNull(perms)
        val p = perms!!
        assertEquals(1, p.size)
        assertEquals("allow", p[0].permission)
        assertEquals("read", p[0].action)
        assertEquals("src/**", p[0].pattern)

        // time
        val t = info.time
        assertNotNull(t)
        assertEquals(100L, t!!.created)
        assertEquals(200L, t!!.updated)
        assertEquals(300L, t!!.archived)

        // revert
        val r = info.revert
        assertNotNull(r)
        assertEquals("msg_001", r!!.messageID)
        assertEquals("part_002", r!!.partID)
        assertEquals("snap_v1", r!!.snapshot)
        assertEquals("diff_content", r!!.diff)
    }

    @Test
    fun `sessionInfo with permission null fields deserializes`() {
        val input = """{
            "id": "ses_003",
            "permission": [
                {"action": "write"},
                {"action": "read", "pattern": "*.kt"}
            ]
        }"""
        val info = json.decodeFromString<SessionInfo>(input)
        val perms = info.permission
        assertNotNull(perms)
        val p = perms!!
        assertEquals(2, p.size)
        // First: no permission, no pattern
        assertNull(p[0].permission)
        assertEquals("write", p[0].action)
        assertNull(p[0].pattern)
        // Second: no permission
        assertNull(p[1].permission)
        assertEquals("read", p[1].action)
        assertEquals("*.kt", p[1].pattern)
    }

    // ─── SessionRevert (already has partID — verify existing) ─────────

    @Test
    fun `sessionRevert with all fields deserializes`() {
        val input = """{
            "messageID": "m1",
            "partID": "p1",
            "snapshot": "snap",
            "diff": "diff"
        }"""
        val revert = json.decodeFromString<SessionRevert>(input)
        assertEquals("m1", revert.messageID)
        assertEquals("p1", revert.partID)
        assertEquals("snap", revert.snapshot)
        assertEquals("diff", revert.diff)
    }

    @Test
    fun `sessionRevert with partial fields deserializes`() {
        val input = """{"messageID": "m2"}"""
        val revert = json.decodeFromString<SessionRevert>(input)
        assertEquals("m2", revert.messageID)
        assertNull(revert.partID)
        assertNull(revert.snapshot)
        assertNull(revert.diff)
    }

    // ─── MessagePart: new types ─────────────────────────────────────────

    @Test
    fun `messagePart type file with name and path deserializes correctly`() {
        val input = """{
            "type": "file",
            "name": "main.kt",
            "path": "/src/main.kt"
        }"""
        val part = json.decodeFromString<MessagePart>(input)
        assertEquals("file", part.type)
        assertEquals("main.kt", part.name)
        assertEquals("/src/main.kt", part.path)
    }

    @Test
    fun `messagePart type file without optional name and path defaults to null`() {
        val input = """{
            "type": "file"
        }"""
        val part = json.decodeFromString<MessagePart>(input)
        assertEquals("file", part.type)
        assertNull(part.name)
        assertNull(part.path)
    }

    @Test
    fun `messagePart type agent deserializes`() {
        val input = """{
            "type": "agent",
            "text": "Switching to reasoning agent"
        }"""
        val part = json.decodeFromString<MessagePart>(input)
        assertEquals("agent", part.type)
        assertEquals("Switching to reasoning agent", part.text)
    }

    @Test
    fun `messagePart type snapshot deserializes`() {
        val input = """{
            "type": "snapshot",
            "text": "Snapshot captured"
        }"""
        val part = json.decodeFromString<MessagePart>(input)
        assertEquals("snapshot", part.type)
        assertEquals("Snapshot captured", part.text)
    }

    @Test
    fun `messagePart type patch deserializes`() {
        val input = """{
            "type": "patch",
            "text": "Applied patch to main.kt"
        }"""
        val part = json.decodeFromString<MessagePart>(input)
        assertEquals("patch", part.type)
        assertEquals("Applied patch to main.kt", part.text)
    }

    @Test
    fun `messagePart type retry deserializes`() {
        val input = """{
            "type": "retry",
            "text": "Retrying tool call"
        }"""
        val part = json.decodeFromString<MessagePart>(input)
        assertEquals("retry", part.type)
        assertEquals("Retrying tool call", part.text)
    }

    @Test
    fun `messagePart type compaction deserializes`() {
        val input = """{
            "type": "compaction",
            "text": "Context window compacted"
        }"""
        val part = json.decodeFromString<MessagePart>(input)
        assertEquals("compaction", part.type)
        assertEquals("Context window compacted", part.text)
    }

    @Test
    fun `messagePart type subtask deserializes`() {
        val input = """{
            "type": "subtask",
            "text": "Starting subtask: code review"
        }"""
        val part = json.decodeFromString<MessagePart>(input)
        assertEquals("subtask", part.type)
        assertEquals("Starting subtask: code review", part.text)
    }

    // ─── MessagePart: backward compatibility ────────────────────────────

    @Test
    fun `messagePart old format without name and path still deserializes`() {
        val input = """{
            "type": "text",
            "text": "Hello world"
        }"""
        val part = json.decodeFromString<MessagePart>(input)
        assertEquals("text", part.type)
        assertEquals("Hello world", part.text)
        assertNull(part.name)
        assertNull(part.path)
    }

    @Test
    fun `messagePart tool format with all legacy fields deserializes`() {
        val input = """{
            "type": "tool",
            "tool": "bash",
            "callID": "call_001",
            "state": {
                "status": "success",
                "input": {"command": "ls -la"},
                "output": "total 42"
            }
        }"""
        val part = json.decodeFromString<MessagePart>(input)
        assertEquals("tool", part.type)
        assertEquals("bash", part.tool)
        assertEquals("call_001", part.callID)
        assertNotNull(part.state)
        assertEquals("success", part.state!!.status)
    }

    @Test
    fun `messagePart with unknown fields is ignored`() {
        val input = """{
            "type": "text",
            "text": "hello",
            "unknown_field": "should_be_ignored",
            "extra_number": 42
        }"""
        val part = json.decodeFromString<MessagePart>(input)
        assertEquals("text", part.type)
        assertEquals("hello", part.text)
    }

    // ─── MessageInfo with new types ─────────────────────────────────────

    @Test
    fun `messageInfo with file type part deserializes correctly`() {
        val input = """{
            "info": {
                "id": "msg_001",
                "role": "assistant"
            },
            "parts": [
                {
                    "type": "file",
                    "name": "build.gradle",
                    "path": "/app/build.gradle"
                }
            ]
        }"""
        val msg = json.decodeFromString<MessageInfo>(input)
        assertEquals("msg_001", msg.id)
        assertEquals("assistant", msg.role)
        assertEquals(1, msg.parts.size)
        val filePart = msg.parts[0]
        assertEquals("file", filePart.type)
        assertEquals("build.gradle", filePart.name)
        assertEquals("/app/build.gradle", filePart.path)
    }

    @Test
    fun `messageInfo with mixed type parts deserializes`() {
        val input = """{
            "info": {
                "id": "msg_002",
                "role": "assistant"
            },
            "parts": [
                {"type": "text", "text": "Let me check"},
                {"type": "agent", "text": "Switching to coder"},
                {"type": "file", "name": "README.md", "path": "/README.md"},
                {"type": "snapshot", "text": "Saved"},
                {"type": "retry", "text": "Retrying"}
            ]
        }"""
        val msg = json.decodeFromString<MessageInfo>(input)
        assertEquals(5, msg.parts.size)
        assertEquals("text", msg.parts[0].type)
        assertEquals("agent", msg.parts[1].type)
        assertEquals("file", msg.parts[2].type)
        assertEquals("README.md", msg.parts[2].name)
        assertEquals("snapshot", msg.parts[3].type)
        assertEquals("retry", msg.parts[4].type)
    }
}
