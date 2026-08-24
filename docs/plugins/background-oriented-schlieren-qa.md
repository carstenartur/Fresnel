# BOS target acceptance criteria

This checklist defines the current target-generation boundary for issue #140.

- [x] Identical canonical parameters and seed produce identical target pixels.
- [x] Different seeds change dot locations without changing the requested dot count.
- [x] Dot diameter and minimum clear spacing are never silently relaxed.
- [x] Infeasible density is rejected explicitly.
- [x] Raster dimensions and total pixel count are bounded before allocation.
- [x] Optional registration fiducials require a sufficiently large dot-free border.
- [x] Browser preview is bounded; exported PNG retains authoritative dimensions.
- [x] Plugin metadata advertises target generation and PNG only.
- [x] The graphical editor, REST endpoints and `.fresnel` files share canonical validation.
- [ ] Reference/disturbed capture persistence — next common measurement-session slice.
- [ ] Manual capture-set import — subsequent BOS analysis slice.
- [ ] Photographer-controlled capture — optional provider adapter.
- [ ] Displacement analysis and visualization — subsequent BOS algorithm slice.
- [ ] Checksummed experiment bundle — subsequent durable artifact slice.
