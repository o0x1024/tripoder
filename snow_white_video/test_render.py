import time, sys
import art
for name in sys.argv[1:]:
    t=time.time()
    img = art.render(name, seed=3)
    img.save(f"frames/test_{name}.png")
    print(name, round(time.time()-t,2), "s", img.size)
