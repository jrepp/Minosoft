<!--
  Minosoft
  Copyright (C) 2026 Jacob Repp

  This program is free software: you can redistribute it and/or modify it under
  the terms of the GNU General Public License as published by the Free Software
  Foundation, either version 3 of the License, or (at your option) any later
  version.
-->

# Medium diverse-biomes visual remediation

The fixed pose at `32.6740054977249, 78, -608.6999999880791`, yaw `-65`, pitch
`0` separated the reported black geometry into independent lighting, seam, and
persistence failures.

- Exposed DH faces inherited zero light from their opaque source voxel instead
  of the adjacent water/air sample.
- Built-in distant water forced alpha to one, turning the ocean continuation
  into a hard dark strip. It now preserves material alpha and fades across the
  native seam. Solid DH fragments are clipped inside that seam as a final
  fragment-precision guard for conservative partial-page selection.
- The detached island came from 1,469 persisted pages on a remote dedicated
  server. A server address and dimension do not identify a replacement level;
  remote persistence now fails closed unless local authority supplies the
  stable terrain fingerprint used by the store identity.

The settled final frame used 423 native and 30 network pages, reported 109 near
coverage cells and 109 masked pages, and had zero queued builds, outstanding
builds, active builds, completions, uploads, submissions, fences, or retired
pages. The black strip, gray seam walls, and detached island were absent in
`.run/visual-inspection/medium-diverse-dh-remote-persistence-guard-final-2026-08-03.png`.

Exact Complementary Unbound r5.8.1 fingerprint
`385d3c1777dc297dde25dc7e5fec55234f593f824a896fd21eca3cde3e6c38a8`
was also checked independently and with DH. Its same-pose motion transient was
13.52x stationary luma immediately after a five-degree return, converging to
1.24x by the 16-frame checkpoint; no persistent geometry or shader-mask defect
remained. The bounded report is
`.run/motion-noise/2026-08-03-medium-diverse-iris-dh/report.json`.
