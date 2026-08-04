package com.example.roku.gradle.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateLayoutStubsTaskTest {

    @Test
    fun `standard builder methods are extracted by named and positional id`() {
        val ids = GenerateLayoutStubsTask.extractNodeIds(
            """
            sceneLayout {
                buttonGroup(id = "content") {
                    button(id = "incrementButton", text = "Click")
                    label("counterLabel", text = "0")
                }
            }
            """.trimIndent()
        )
        assertEquals(listOf("content", "incrementButton", "counterLabel"), ids)
    }

    @Test
    fun `embedded component with named id gets an accessor`() {
        val ids = GenerateLayoutStubsTask.extractNodeIds(
            """
            sceneLayout {
                layoutGroup(
                    id = "mainLayout",
                    layoutDirection = LayoutDirection.horiz
                ) {
                    component("ShelfView",
                        id = "shelf_view",
                        focusable = true
                    )
                }
            }
            """.trimIndent()
        )
        assertEquals(listOf("mainLayout", "shelf_view"), ids)
    }

    @Test
    fun `embedded component with positional type and id gets an accessor`() {
        val ids = GenerateLayoutStubsTask.extractNodeIds(
            """component("ShelfView", "shelf_view", focusable = true)"""
        )
        assertEquals(listOf("shelf_view"), ids)
    }

    @Test
    fun `component type name is never mistaken for a node id`() {
        val ids = GenerateLayoutStubsTask.extractNodeIds(
            """component("ShelfView", id = "shelf_view")"""
        )
        assertFalse("ShelfView" in ids)
    }

    @Test
    fun `builder names are not matched as suffixes of longer identifiers`() {
        val ids = GenerateLayoutStubsTask.extractNodeIds(
            """
            relabel(id = "notANode")
            myComponent("Type", id = "alsoNot")
            """.trimIndent()
        )
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `extractLayoutInfo returns class and package with component ids included`() {
        val info = GenerateLayoutStubsTask.extractLayoutInfo(
            """
            package com.example.screens

            class MainScreen : SceneComponent() {
                companion object {
                    @SGLayout
                    fun defineLayout() = sceneLayout {
                        layoutGroup(id = "mainLayout") {
                            component("ShelfView", id = "shelf_view")
                        }
                    }
                }
            }
            """.trimIndent()
        )
        assertEquals("com.example.screens", info?.packageName)
        assertEquals("MainScreen", info?.className)
        assertEquals(listOf("mainLayout", "shelf_view"), info?.nodeIds)
    }

    @Test
    fun `no SGLayout annotation means no stub`() {
        assertNull(GenerateLayoutStubsTask.extractLayoutInfo("class Foo { fun bar() {} }"))
    }
}
