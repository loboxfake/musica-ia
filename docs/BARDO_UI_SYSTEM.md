# BARDO — UI system and product structure

Version: 2026-11-08 · UI revision 2

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


## Experience structure — implemented refinement

The six workspaces remain the stable product map. The Assistant home now also offers direct, accessible shortcuts to Studio, Library and Evolution; these shortcuts invoke the same existing navigation listeners and do not create duplicate navigation state.

Studio is presented as a deliberate creation flow: choose complete guided song, user-supplied lyrics, or instrumental; attach a user voice reference only when needed. The interface explicitly states that recording/importing a voice is not voice cloning or generated singing. The actual PCM voice/instrumental mixing path remains distinct from any future voice synthesis.

Evolution separates the visual hierarchy into local models, memory/knowledge and development autonomy. Gemma, audio generation and Bonsai remain independent model states. A model in Downloads is not marked active until the app has imported and verified its private copy and the runtime checks succeed.

The visual refinement uses quieter ink-navy surfaces, thin cool borders, a more restrained cyan/violet primary gradient, clearer input contrast and a stronger active navigation state. It avoids bright blue card outlines and decorative gold borders. Touch targets remain at least 48dp where applicable; the six configurable navigation items and all existing view IDs/listeners are preserved.

The user's confirmed model location is Downloads/BARDO/Modelos. The UI offers linking/importing that existing model and does not initiate another download. SAF permission remains required by Android; the original model file is preserved.


## BARDO avatar — first chat integration

The main chat uses the actual launcher foreground artwork as BARDO's avatar, preserving the feather, musical notes, gold detail, cyan and violet identity. A lightweight activity ring and status dot distinguish observable states: idle, listening/typing, reading chat, studying/importing a file, thinking, responding, generating, waiting, completed and attention/error.

The avatar is deliberately a status representation, not a claim of consciousness or human emotion. Its state is driven by chat input, scroll activity, local response flow, attachment analysis and the audio task service. A low-amplitude pulse runs only while an active state is visible; it stops when the view is hidden, detached or the window is not visible. The main chat remains usable if animation is unavailable.

Future BARDO self-evolution may propose avatar, interface, code, skill and capability improvements through isolated diffs, previews, objective tests and rollback. Integration still follows the existing approval and protected-file rules.
