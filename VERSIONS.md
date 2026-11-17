# Side Toolbar version checkpoints

Version tags identify source snapshots that can be downloaded or checked out independently. The development labels are intentional; a tag is a rollback point, not a new claim of stable-release status. Historical experiments retain their original behavior.

The current implementation remains on `main`. Missing historical checkpoints were recovered on archive branches without rewriting existing published history. Recovered code was compiled against ImageJ 1.54p targeting Java 8; this does not repeat its original physical-input acceptance tests.

| Version tag | Commit | Checkpoint |
|---|---|---|
| [v0.1.0](https://github.com/AIHector/Fiji-Side-Toolbar/tree/v0.1.0) | [73d52eb](https://github.com/AIHector/Fiji-Side-Toolbar/commit/73d52ebbcbcbcefb2c08df434f2df104f0a6eaa4) | historical original drawing toolbar |
| [v0.2.0](https://github.com/AIHector/Fiji-Side-Toolbar/tree/v0.2.0) | [447b726](https://github.com/AIHector/Fiji-Side-Toolbar/commit/447b7264c2530645eb78d93448fd274bb2112056) | previously published version |
| [v0.2.1](https://github.com/AIHector/Fiji-Side-Toolbar/tree/v0.2.1) | [508033d](https://github.com/AIHector/Fiji-Side-Toolbar/commit/508033dfdbda63f6afcf85cd97b392fb64c83a94) | previously published version |
| [v0.2.2](https://github.com/AIHector/Fiji-Side-Toolbar/tree/v0.2.2) | [63b9cee](https://github.com/AIHector/Fiji-Side-Toolbar/commit/63b9ceeddddd12fed8630a5a7a7c372b7cff30ee) | previously published version |
| [v0.2.3](https://github.com/AIHector/Fiji-Side-Toolbar/tree/v0.2.3) | [0ca9a55](https://github.com/AIHector/Fiji-Side-Toolbar/commit/0ca9a551bb7ad0175ca0a6746705a1c463953e7c) | previously published version |
| [v0.2.4](https://github.com/AIHector/Fiji-Side-Toolbar/tree/v0.2.4) | [3b87800](https://github.com/AIHector/Fiji-Side-Toolbar/commit/3b87800ca84f73cc5e6901af2c029acc3096915c) | previously published version |
| [v0.2.5-dev1](https://github.com/AIHector/Fiji-Side-Toolbar/tree/v0.2.5-dev1) | [4ca6163](https://github.com/AIHector/Fiji-Side-Toolbar/commit/4ca616339249ee1935da3a055dce09344f2d54d0) | historical native-tool guard; superseded |
| [v0.3.0-dev1](https://github.com/AIHector/Fiji-Side-Toolbar/tree/v0.3.0-dev1) | [de408de](https://github.com/AIHector/Fiji-Side-Toolbar/commit/de408de25c827a4b5f1970f96e6d42934d177f6e) | current installed editable toolbars |

## Alternative or same-number snapshots

These tags distinguish different implementations that shared a VERSION value. They are separate development lines.

| Archive tag | Commit | Checkpoint |
|---|---|---|
| [archive/v0.3.0-macro-buttons](https://github.com/AIHector/Fiji-Side-Toolbar/tree/archive/v0.3.0-macro-buttons) | [ffced49](https://github.com/AIHector/Fiji-Side-Toolbar/commit/ffced49f4fc1ac3bba133fc9a3efcfeadffff5d8) | alternative historical macro-button line |

## Returning to a version

1. Follow a tag above, then use **Code > Download ZIP**, or check out that tag in a separate local clone.
2. Build from that version’s source using its included instructions and a compatible Fiji installation. Tags preserve source, guides, startup macros and available tests; they do not automatically attach an installable jar. Historical build scripts can install directly, so save your work and quit Fiji before running a script that copies files into Fiji.
3. Preserve the currently installed plugin jar and startup macro, then replace them with the chosen build and reopen Fiji. Keep your image files and preferences. Companion plugins have independent version tags.

For a recently installed development version, an exact local jar/settings backup can also be restored with Fiji closed. Switching a Git tag alone does not change an installed Fiji.
