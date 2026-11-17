# Verified companion-model recognition

The optional local IFC supplement identifies supported vertical straight Stekanker components and T-section transport beams from a project companion model. It is independent of the chat model/provider and does not request inference or export project data.

## Evidence required

- A private `data_lokaal/reference_models.json` registry binds a project to an IFC file and original one-page drawings using SHA-256. The registry contains source pointers, never per-article approval flags.
- The current technical drawing fingerprint must equal the registered original, including technical text, geometry, project and revision. Only the nontechnical date stamp is excluded. The checker captures provenance before adding its own legend or OCR/markings; this internal page attribute cannot be supplied by document text.
- The IFC project must explicitly assign millimetre length units. Only bounded IFC2X3 faceted-BREP geometry and validated orthonormal nested placements are supported. Unsupported geometry or stale/missing sources retain ordinary manual review.
- The full wall projection must match current native vertices. Component names, material/type variants, complete quantities, projected contours and interior silhouette strokes must match. A few coincident vertices, a shared colour, partial occurrences or a conveniently matching count are insufficient. Straight declared anchor length is checked against the reference body.
- Coordinate systems are evaluated in wall-local axes, including translated/rotated buildings and mirrored views. Ambiguous accepted constellations are rejected.
- Approval is limited to unbent anchors satisfying the engine's existing one-axis rule, with an actual drawn X-ordinate for every individually verified component. Model coordinates alone are never drawn-dimension evidence. Other identified components keep a specific placement-unconfirmed finding. Existing explicit labels/errors are not overridden by this supplement.

## Private registry

Example structure (synthetic paths only):

```json
{"schema":1,"projects":[{"project":"999001","ifc":{"path":"C:/References/999001.ifc","sha256":"..."},"drawings":[{"path":"C:/References/999001-V.pdf","sha256":"...","page":1}]}]}
```

Add references only after inspecting their provenance and agreement. References remain local and outside Git; they must be available on the machine running the engine. Updating a drawing or model invalidates stale hashes/fingerprints and restores ordinary review until the new sources are verified. The module does not search arbitrary disks or download models. Apply relevant shared corrections independently in both engine installations, preserving unrelated edits and backups.

Tests: `test_ifc_reference.py`; original-project evidence and regenerated reports remain in the local audit. Supported geometry does not imply engineering approval or universal IFC/drawing interpretation.
