# BARDO — UI system and product structure

Version: 2026-11-08

## Product frame

BARDO is a local-first personal system with a creative studio, not only a song generator. The interface is organized around the central orchestrator and six persistent workspaces:

1. **Assistant** — general conversation, user intent, approved memory, imports, activity and history.
2. **Studio** — guided song creation, user lyrics, instrumental generation and the audio/voice tools that are actually available.
3. **Library** — generated and imported audio, playlists, favourites, playback, basic WAV multitrack mixing and basic mastering.
4. **Images** — cover art linked to a song/album, free image requests, and procedural cover design.
5. **Evolution** — Gemma, local engines, model catalogue, knowledge, memory, capability registry and BARDO Developer.
6. **System** — authorized Downloads/BARDO workspace, permissions, backup, preferences and capability status.

The sticky brand header and bottom workspace navigation keep the application oriented while long workspaces scroll independently. Existing navigation IDs and settings persistence remain stable.

## Visual language

- Ink navy foundations; layered navy surfaces with restrained gradients and fine borders.
- Cyan means local activity and primary interaction; violet supports creative/model identity; warm gold is a rare brand accent.
- Typography uses a strong workspace title, a brief explanatory line, short section labels and visible action hierarchy.
- One clear primary action per module; secondary actions share a quiet outline style.
- Chat and form inputs have a distinct surface, adequate contrast and comfortable touch dimensions.
- No permanent neon glow, glass blur, excessive decorative cards or fake capability states.
- The design must work on narrow Android displays, with keyboard resizing, accessible labels and scrollable long content.

## Capability truth

Every module must expose its real state and prerequisites. A component being present in the APK does not mean a model is installed, the device has enough RAM, or the feature has passed a physical test. Future concepts—stems, advanced vocal editing, professional mixing/mastering, skills/plugins and richer project sessions—extend the existing workspaces but are not presented as finished features.

Bonsai Image 4B is an optional local model. Procedural PNG cover art is deterministic graphics, not image-generation AI. Stable Audio/ExecuTorch is distinguished from future singing-voice synthesis. The model status view must show Gemma, audio and Bonsai independently.

## User files and local model

The user's established working directory is Downloads/BARDO, with the already downloaded Gemma model in Downloads/BARDO/Modelos. Android Storage Access Framework authorization is required to read that tree. Once authorized, BARDO searches for the exact `gemma-4-E2B-it.litertlm` file, verifies the published size and SHA-256, and imports a private app copy. It never deletes the original or starts a duplicate download as the default action. If Android revokes the persisted tree permission, ask the user to reconnect Downloads/BARDO through the system folder picker.

## Evolution rules

- Keep the central orchestrator and module boundaries; add new capabilities as modules with resource, model, permission, licensing and acceptance metadata.
- Preserve the user's navigation order/visibility preferences and conversation/project data.
- New visual states must be backed by observable code, not placeholder labels.
- Never report a module as device-tested without actual device evidence.
- Preserve `Modo3MezclaVozUsuario.kt`, signing material and security controls unless separately and explicitly authorized.
