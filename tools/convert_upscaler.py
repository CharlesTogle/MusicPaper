"""
Builds app/src/main/assets/upscaler_x4.tflite from Real-ESRGAN's realesr-general-x4v3 weights (BSD-3-Clause).

    uv venv -p 3.12 .venv && source .venv/bin/activate
    uv pip install torch --index-url https://download.pytorch.org/whl/cpu
    uv pip install litert-torch
    curl -LO https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth
    python tools/convert_upscaler.py realesr-general-x4v3.pth app/src/main/assets/upscaler_x4.tflite

The model takes a fixed 128x128 RGB tile (NHWC, float 0..1) and returns a 512x512 tile.
A fixed size keeps the GPU delegate happy; the app splits covers into overlapping tiles.
"""
import sys

import torch
import torch.nn as nn
import torch.nn.functional as F

TILE = 128
SCALE = 4


class SRVGGNetCompact(nn.Module):
    """Same layout as basicsr's SRVGGNetCompact, so the released weights load unchanged."""

    def __init__(self, num_feat=64, num_conv=32, upscale=SCALE):
        super().__init__()
        self.upscale = upscale
        body = [nn.Conv2d(3, num_feat, 3, 1, 1), nn.PReLU(num_parameters=num_feat)]
        for _ in range(num_conv):
            body += [nn.Conv2d(num_feat, num_feat, 3, 1, 1), nn.PReLU(num_parameters=num_feat)]
        body += [nn.Conv2d(num_feat, 3 * upscale * upscale, 3, 1, 1)]
        self.body = nn.ModuleList(body)
        self.upsampler = nn.PixelShuffle(upscale)

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        out = self.upsampler(out)
        base = F.interpolate(x, scale_factor=self.upscale, mode="nearest")
        return (out + base).clamp(0.0, 1.0)


def main(weights: str, out_path: str):
    import litert_torch

    model = SRVGGNetCompact()
    state = torch.load(weights, map_location="cpu")
    model.load_state_dict(state.get("params", state), strict=True)
    model.eval()

    sample = (torch.rand(1, 3, TILE, TILE),)
    nhwc = litert_torch.to_channel_last_io(model, args=[0], outputs=[0])
    edge = litert_torch.convert(nhwc, (torch.rand(1, TILE, TILE, 3),))

    # Check the converted model matches PyTorch before saving.
    x = sample[0]
    want = model(x).permute(0, 2, 3, 1).detach().numpy()
    got = edge(x.permute(0, 2, 3, 1).numpy())
    err = abs(want - got).max()
    print(f"max difference vs PyTorch: {err:.6f}")
    assert err < 1e-3, "converted model does not match"

    edge.export(out_path)
    print(f"wrote {out_path}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
