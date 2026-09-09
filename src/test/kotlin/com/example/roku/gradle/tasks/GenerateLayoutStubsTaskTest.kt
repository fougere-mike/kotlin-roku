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

// ---------------------------------------------------------------------------
// Typed component builders (spec 2026-09-04-component-lifecycle §6)
// ---------------------------------------------------------------------------

class GenerateLayoutStubsTaskBuilderTest {

    @Test
    fun parsesComponentDeclarationWithInputs() {
        val src = """
            package app.screens
            import kotlin.brs.GroupComponent
            import kotlin.brs.SGStringField
            import kotlin.brs.SGIntegerField
            class AiringDetailsScreen(@SGStringField val airingId: String, @SGIntegerField(alwaysNotify = true) val row: Int) : GroupComponent()
            class Plain : GroupComponent()
        """.trimIndent()
        val infos = GenerateLayoutStubsTask.extractComponentInfos(src)
        assertEquals(listOf("AiringDetailsScreen", "Plain"), infos.map { it.className })
        assertEquals(listOf("airingId" to "String", "row" to "Int"), infos[0].inputs)
        assertEquals("airingDetailsScreen", infos[0].builderName)
        assertEquals("app.screens", infos[0].packageName)
        assertTrue(infos[1].inputs.isEmpty())
    }

    @Test
    fun parsesMultiLineHeaderWithDefaultsCommentsAndBrsField() {
        val src = """
            package app
            class Card(
                @SGStringField val title: String = "untitled", // shown in the header
                /** legacy spelling */ @BrsField(type = "boolean") var pinned: Boolean,
                @SGFloatField val weight: Float
            ) : RectangleComponent() {
                fun describe() = title
            }
        """.trimIndent()
        val infos = GenerateLayoutStubsTask.extractComponentInfos(src)
        assertEquals(listOf("Card"), infos.map { it.className })
        // A Kotlin default does not make the input optional: the compiler requires every
        // annotated constructor property for the ready marker, so the builder requires it too.
        assertEquals(listOf("title" to "String", "pinned" to "Boolean", "weight" to "Float"), infos[0].inputs)
    }

    @Test
    fun skipsIneligibleComponents() {
        val src = """
            package app
            import kotlin.brs.GroupComponent
            import kotlin.brs.SGNodeField
            import kotlin.brs.roku.RoSGNode
            class NodeInput(@SGNodeField val owner: RoSGNode?) : GroupComponent()
            class Label : GroupComponent()
            class Object : GroupComponent()
            class Shadowing(@SGBooleanField val visible: Boolean) : GroupComponent()
            class PlainParam(val count: Int) : GroupComponent()
            abstract class BaseScreen : GroupComponent()
            class NotAComponent(val x: Int) : SomethingElse()
        """.trimIndent()
        val skipped = mutableListOf<GenerateLayoutStubsTask.SkippedComponent>()
        val infos = GenerateLayoutStubsTask.extractComponentInfos(src, skipped = skipped)
        assertTrue(infos.isEmpty())
        val byName = skipped.associateBy { it.className }
        assertEquals(setOf("NodeInput", "Label", "Object", "Shadowing", "PlainParam", "BaseScreen"), byName.keys)
        assertFalse(byName.getValue("NodeInput").isWarning)   // node-typed input: construct in code (designed-in)
        assertFalse(byName.getValue("BaseScreen").isWarning)  // abstract base: designed-in
        assertTrue(byName.getValue("Label").isWarning)        // collides with a built-in DSL method
        assertTrue(byName.getValue("Object").isWarning)       // Kotlin hard keyword
        assertTrue(byName.getValue("Shadowing").isWarning)    // input reuses a standard-attribute name
        assertTrue(byName.getValue("PlainParam").isWarning)   // unparseable: not an @SG input
    }

    @Test
    fun resolvesBaseChainsAcrossFilesAndWithinAFile() {
        val baseFile = """
            package app
            open class BaseScreen : GroupComponent()
        """.trimIndent()
        val childFile = """
            package app
            class Home : BaseScreen()
            class Guide : Home()
            class Stranger : Unrelated()
        """.trimIndent()
        val known = GenerateLayoutStubsTask.collectComponentClassNames(listOf(baseFile, childFile))
        assertEquals(setOf("BaseScreen", "Home", "Guide"), known)
        // Without the module-wide set the child file alone resolves nothing...
        assertTrue(GenerateLayoutStubsTask.extractComponentInfos(childFile).isEmpty())
        // ...with it, both descendants get builders; the open base does too (it is instantiable).
        assertEquals(listOf("Home", "Guide"), GenerateLayoutStubsTask.extractComponentInfos(childFile, known).map { it.className })
        assertEquals(listOf("BaseScreen"), GenerateLayoutStubsTask.extractComponentInfos(baseFile, known).map { it.className })
    }

    @Test
    fun rendersABuilder() {
        val info = GenerateLayoutStubsTask.ComponentInfo("app.screens", "AiringDetailsScreen", listOf("airingId" to "String"))
        val text = GenerateLayoutStubsTask.renderBuilder(info)
        assertTrue(text.contains("@SGComponentBuilder(\"AiringDetailsScreen\")"))
        assertTrue(text.contains("fun LayoutBuilder.airingDetailsScreen(id: String, airingId: String, translation: Vector2D? = null"))
        assertTrue(text.contains(", renderPass: Int? = null, init: ComponentBuilder.() -> Unit = {}) {"))
        assertTrue(text.contains("component(\"AiringDetailsScreen\", id = id, translation = translation, rotation = rotation"))
        assertTrue(text.contains(", focusable = focusable, renderPass = renderPass) {"))
        assertTrue(text.contains("attr(\"airingId\", airingId)"))
        assertTrue(text.trimEnd().endsWith("        init()\n    }\n}"))
    }

    @Test
    fun rendersTheBuildersFileWithPackageAndImports() {
        val infos = listOf(
            GenerateLayoutStubsTask.ComponentInfo("app", "Badge", listOf("label" to "String")),
            GenerateLayoutStubsTask.ComponentInfo("app", "Plain", emptyList()),
        )
        val text = GenerateLayoutStubsTask.renderBuildersFile("app", infos)
        assertTrue(text.startsWith("// AUTO-GENERATED - DO NOT EDIT"))
        assertTrue(text.contains("\npackage app\n"))
        for (imported in listOf("ComponentBuilder", "LayoutBuilder", "SGComponentBuilder", "Vector2D", "Vector4D")) {
            assertTrue("missing import $imported", text.contains("import kotlin.brs.scenegraph.$imported\n"))
        }
        assertTrue(text.contains("fun LayoutBuilder.badge(id: String, label: String, translation"))
        assertTrue(text.contains("fun LayoutBuilder.plain(id: String, translation"))
    }

    @Test
    fun buildersFileNameIsUniquePerPackage() {
        // Output .brs names are flat (<file>Kt.brs) — two packages must not share a file name.
        assertEquals("ComponentBuilders.kt", GenerateLayoutStubsTask.buildersFileName(""))
        assertEquals("ComponentBuilders_com_nuvyyo_roku_components.kt", GenerateLayoutStubsTask.buildersFileName("com.nuvyyo.roku.components"))
        assertEquals("ComponentBuilders_com_nuvyyo_roku_components_fixtures.kt", GenerateLayoutStubsTask.buildersFileName("com.nuvyyo.roku.components.fixtures"))
    }

    @Test
    fun builderCallsYieldNodeIds() {
        val src = """sceneLayout { airingDetailsScreen(id = "details", airingId = "123") }"""
        assertEquals(listOf("details"), GenerateLayoutStubsTask.extractNodeIds(src, setOf("airingDetailsScreen")))
    }

    @Test
    fun builderCallsYieldNodeIdsInBothShapesBesideStandardMethods() {
        val src = """
            sceneLayout {
                layoutGroup(id = "root") {
                    badge(id = "hostBadge", label = "NEW", visible = true)
                    badge("secondBadge", "OLD")
                    label(id = "caption")
                }
            }
        """.trimIndent()
        assertEquals(listOf("root", "caption", "hostBadge", "secondBadge"), GenerateLayoutStubsTask.extractNodeIds(src, setOf("badge")))
        // Unknown builder names are never guessed; the single-arg form still works.
        assertEquals(listOf("root", "caption"), GenerateLayoutStubsTask.extractNodeIds(src))
        // A builder name is matched as a whole word only.
        assertTrue(GenerateLayoutStubsTask.extractNodeIds("""rebadge(id = "nope")""", setOf("badge")).isEmpty())
    }

    @Test
    fun extractLayoutInfoIncludesBuilderIds() {
        val info = GenerateLayoutStubsTask.extractLayoutInfo(
            """
            package com.example.screens

            class BadgeHost : GroupComponent() {
                companion object {
                    @SGLayout
                    fun defineLayout() = sceneLayout {
                        badge(id = "hostBadge", label = "NEW")
                    }
                }
            }
            """.trimIndent(),
            setOf("badge")
        )
        assertEquals("BadgeHost", info?.className)
        assertEquals(listOf("hostBadge"), info?.nodeIds)
    }
}
