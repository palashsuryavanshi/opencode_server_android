package com.palash.opencodeserver.backend.deps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 7/8/10/11 - the dependency catalogue and its version rules.
 *
 * The catalogue is the single source of truth, so these tests guard its invariants rather than the
 * behaviour of any particular installer.
 */
class DependencyCatalogTest {

    @Test
    fun everyDependencyHasADisplayNameAndPurpose() {
        DependencyCatalog.all.forEach { spec ->
            assertTrue("${spec.id} needs a display name", spec.displayName.isNotBlank())
            assertTrue("${spec.id} needs a purpose", spec.id.purpose.isNotBlank())
        }
    }

    @Test
    fun specDisplayNameIsDelegatedToItsId() {
        // The convenience accessor exists so callers never reach through `id` for a label.
        DependencyCatalog.all.forEach { spec ->
            assertEquals(spec.id.displayName, spec.displayName)
        }
    }

    @Test
    fun everyDependencyDeclaresHowToInstallIt() {
        DependencyCatalog.all.forEach { spec ->
            assertTrue(
                "${spec.id} must declare an install method",
                spec.installMethod in InstallMethod.entries
            )
        }
    }

    @Test
    fun packageManagerDependenciesDeclarePackageNames() {
        DependencyCatalog.all
            .filter { it.installMethod == InstallMethod.PACKAGE_MANAGER && it.id != DependencyId.SHELL }
            .forEach { spec ->
                assertTrue(
                    "${spec.id} uses the package manager but declares no package name",
                    spec.packages.isNotEmpty() || spec.executables.isNotEmpty()
                )
            }
    }

    @Test
    fun openCodeDeclaresTheDocumentedNpmPackage() {
        val openCode = DependencyCatalog.byId(DependencyId.OPENCODE)
        assertEquals("opencode-ai", openCode.npmPackage)
    }

    @Test
    fun openCodeIsRequiredForTheServer() {
        assertTrue(DependencyCatalog.byId(DependencyId.OPENCODE).requiredForServer)
    }

    @Test
    fun gitAndNodeAreNotServerRequirements() {
        // Spec 5 treats these as degradation sources, not blockers: the server runs without them.
        assertFalse(DependencyCatalog.byId(DependencyId.GIT).requiredForServer)
        assertFalse(DependencyCatalog.byId(DependencyId.NODE).requiredForServer)
    }

    @Test
    fun nodeDeclaresAMinimumVersion() {
        assertEquals("18", DependencyCatalog.byId(DependencyId.NODE).version.minimum)
    }

    @Test
    fun installOrderHasRuntimeBeforeNodeBeforeOpenCode() {
        val order = DependencyCatalog.all.map { it.id }
        assertTrue(order.indexOf(DependencyId.RUNTIME) < order.indexOf(DependencyId.NODE))
        assertTrue(order.indexOf(DependencyId.NODE) < order.indexOf(DependencyId.OPENCODE))
        assertTrue(order.indexOf(DependencyId.GIT) < order.indexOf(DependencyId.OPENCODE))
    }

    @Test
    fun checkpointsMapToTheSpecVocabulary() {
        assertEquals(
            com.palash.opencodeserver.backend.core.SetupCheckpoint.RUNTIME_READY,
            DependencyCatalog.checkpointFor(DependencyId.RUNTIME)
        )
        assertEquals(
            com.palash.opencodeserver.backend.core.SetupCheckpoint.NODE_READY,
            DependencyCatalog.checkpointFor(DependencyId.NODE)
        )
        assertEquals(
            com.palash.opencodeserver.backend.core.SetupCheckpoint.OPENCODE_READY,
            DependencyCatalog.checkpointFor(DependencyId.OPENCODE)
        )
    }

    @Test
    fun requiredBytesExcludesOptionalEntries() {
        val required = DependencyCatalog.requiredBytes(includeOptional = false)
        val withOptional = DependencyCatalog.requiredBytes(includeOptional = true)

        assertTrue(required > 0)
        assertTrue("Optional entries should add to the estimate", withOptional >= required)
    }

    @Test
    fun estimatesAreRealisticRatherThanZero() {
        // Spec 74 requires calculated requirements, not hard-coded ones; a zero here would defeat the
        // storage precheck entirely.
        listOf(DependencyId.RUNTIME, DependencyId.NODE, DependencyId.OPENCODE).forEach { id ->
            assertTrue("${id} needs a size estimate", DependencyCatalog.byId(id).estimatedBytes > 0)
        }
    }
}

/** Spec 7/8 - version comparison, which is what decides "outdated" versus "installed". */
class VersionConstraintTest {

    @Test
    fun acceptsExactMatch() {
        val constraint = VersionConstraint(exact = "1.2.3")
        assertTrue(constraint.accepts("1.2.3"))
        assertTrue(constraint.accepts("v1.2.3"))
        assertFalse(constraint.accepts("1.2.4"))
    }

    @Test
    fun acceptsNewerThanMinimum() {
        val constraint = VersionConstraint(minimum = "18")
        assertTrue(constraint.accepts("18.0.0"))
        assertTrue(constraint.accepts("20.11.1"))
        assertTrue(constraint.accepts("v22.0.0"))
        assertFalse(constraint.accepts("16.20.0"))
    }

    @Test
    fun rejectsNullVersion() {
        assertFalse(VersionConstraint(minimum = "18").accepts(null))
    }

    @Test
    fun anyVersionIsAcceptedWithoutConstraints() {
        val constraint = VersionConstraint()
        assertTrue(constraint.accepts("anything"))
        assertTrue(constraint.accepts("0.0.1"))
    }

    @Test
    fun suffixesAreIgnoredForComparison() {
        // Release metadata such as "-arm64" or "-rc1" must not change the ordering.
        assertTrue(VersionConstraint(minimum = "18").accepts("20.0.0-arm64"))
    }

    @Test
    fun preReleaseIsBelowItsRelease() {
        assertEquals(-1, VersionConstraint.compare("1.0.0-rc1", "1.0.0"))
    }

    @Test
    fun numericComparisonIsNotLexicographic() {
        // The classic bug this guards: "10" < "9" as strings.
        assertTrue(VersionConstraint.compare("10.0.0", "9.0.0") > 0)
    }

    @Test
    fun missingComponentsAreTreatedAsZero() {
        assertEquals(0, VersionConstraint.compare("2", "2.0.0"))
    }

    @Test
    fun describeExplainsTheConstraint() {
        assertEquals("any version", VersionConstraint().describe())
        assertEquals("18 or newer", VersionConstraint(minimum = "18").describe())
        assertEquals("exactly 1.2.3", VersionConstraint(exact = "1.2.3").describe())
    }
}