package me.rerere.rikkahub.data.ai.tools

import me.rerere.workspace.WorkspaceStorageMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxRuntimeRouterTest {
    @Test fun `shared build routes to native Termux`() {
        assertEquals(
            LinuxProfileType.TERMUX_NATIVE,
            LinuxRuntimeRouter.route(LinuxRouteRequest(workspaceMode = WorkspaceStorageMode.SHARED)),
        )
    }

    @Test fun `private or Ubuntu task routes to Workspace PRoot`() {
        assertEquals(
            LinuxProfileType.WORKSPACE_PROOT,
            LinuxRuntimeRouter.route(LinuxRouteRequest(workspaceMode = WorkspaceStorageMode.PRIVATE)),
        )
        assertEquals(
            LinuxProfileType.WORKSPACE_PROOT,
            LinuxRuntimeRouter.route(LinuxRouteRequest(ubuntuRequired = true)),
        )
    }

    @Test fun `explicit profile is never silently replaced`() {
        assertEquals(
            LinuxProfileType.TERMUX_NATIVE,
            LinuxRuntimeRouter.route(
                LinuxRouteRequest(
                    requested = LinuxProfileType.TERMUX_NATIVE,
                    workspaceMode = WorkspaceStorageMode.PRIVATE,
                    ubuntuRequired = true,
                ),
            ),
        )
    }
}
