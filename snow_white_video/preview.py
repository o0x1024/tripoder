import sys
import numpy as np
from PIL import Image
RAMP = " .:-=+*#%@"
def show(path, cols=104, aspect=0.46):
    im = Image.open(path).convert("L")
    w,h = im.size
    rows = max(1,int(cols*(h/w)*aspect))
    im2 = im.resize((cols,rows), Image.LANCZOS)
    a = np.asarray(im2, np.float32)/255.0
    a = (a**0.85)
    print("="*20, path.split('/')[-1], im.size)
    for r in a:
        print("".join(RAMP[min(len(RAMP)-1,int(v*(len(RAMP)-1)+0.5))] for v in r))
    rgb = Image.open(path).convert("RGB").resize((8,5), Image.LANCZOS)
    arr = np.asarray(rgb)
    print("palette 8x5:")
    for row in arr:
        print(" ".join("%02x%02x%02x"%tuple(px) for px in row))
for p in sys.argv[1:]:
    show(p)
