<!-- Copyright (C) 2026 Jacob Repp -->

# Headless render contracts

This module owns rendering contracts that must compile and test without the
Minosoft application, Minecraft state, a window, LWJGL, or OpenGL:

- immutable render graph descriptions and owner-scoped publication;
- typed render target and vertex-layout declarations;
- leased candidate/publish/retire generation ownership; and
- fixed-storage terrain performance telemetry.

The application module consumes these contracts and owns concrete world,
renderer, compatibility-adapter, and GPU implementations. Keep dependencies
pointing from the application to this module; do not add an application
dependency here to make a concrete implementation compile.
