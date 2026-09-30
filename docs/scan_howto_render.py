"""Renders the 'how to scan' demonstration video: a person with a phone walking a room,
seen from above, with the surfaces the camera has covered turning green. No text
inside the frames (the app shows captions per scene), only REC / check / cross marks."""
import math, subprocess, sys
from PIL import Image, ImageDraw
import imageio_ffmpeg

W, H, FPS = 960, 540, 30
BG = (30, 37, 48); FLOOR = (44, 52, 64); WALL = (216, 222, 233); WALL_DONE = (76, 220, 122)
PERSON = (255, 160, 0); PHONE = (255, 255, 255); CONE = (76, 195, 255, 70); CONE_EDGE = (76, 195, 255, 160)
RED = (255, 82, 82); GREEN = (76, 255, 122); FURN = (120, 100, 80); GLASS = (120, 180, 255); TEXT = (255, 255, 255)
PX = 100  # pixels per metre

def clamp(x, a, b): return max(a, min(b, x))
def ease(t): t = clamp(t, 0, 1); return t * t * (3 - 2 * t)

class Scene:
    def __init__(self, walls, furniture=(), origin=(180, 90)):
        self.ox, self.oy = origin
        self.walls = walls          # list of ((x1,y1),(x2,y2)) metres
        self.furniture = furniture  # list of (x,y,w,h, kind)
        self.cover = [[] for _ in walls]  # covered fractions per wall as (a,b)
        self.cover_f = [False] * len(furniture)
    def P(self, x, y): return (self.ox + x * PX, self.oy + y * PX)

def draw_room(d, s, img):
    # floor
    xs = [p[0] for w in s.walls for p in w]; ys = [p[1] for w in s.walls for p in w]
    d.rectangle([s.P(min(xs), min(ys)), s.P(max(xs), max(ys))], fill=FLOOR)
    for (x, y, w, h, kind), done in zip(s.furniture, s.cover_f):
        col = WALL_DONE if done else (FURN if kind == 'table' else GLASS)
        d.rectangle([s.P(x, y), s.P(x + w, y + h)], fill=col, outline=(0, 0, 0))
    for i, ((x1, y1), (x2, y2)) in enumerate(s.walls):
        d.line([s.P(x1, y1), s.P(x2, y2)], fill=WALL, width=10)
        for a, b in s.cover[i]:
            pa = s.P(x1 + (x2 - x1) * a, y1 + (y2 - y1) * a); pb = s.P(x1 + (x2 - x1) * b, y1 + (y2 - y1) * b)
            d.line([pa, pb], fill=WALL_DONE, width=10)

def add_cover(s, px, py, ang, fov=math.radians(60), reach=2.6):
    """Marks the walls (and furniture) inside the cone from (px,py) metres looking along ang."""
    for i, ((x1, y1), (x2, y2)) in enumerate(s.walls):
        n = 40; segs = []
        for k in range(n):
            t = (k + 0.5) / n
            x = x1 + (x2 - x1) * t; y = y1 + (y2 - y1) * t
            dx, dy = x - px, y - py; dist = math.hypot(dx, dy)
            if dist > reach: continue
            da = (math.atan2(dy, dx) - ang + math.pi) % (2 * math.pi) - math.pi
            if abs(da) < fov / 2: segs.append(t)
        for t in segs: s.cover[i].append((t - 0.5 / n, t + 0.5 / n))
    for j, (x, y, w, h, kind) in enumerate(s.furniture):
        cx, cy = x + w / 2, y + h / 2
        dx, dy = cx - px, cy - py; dist = math.hypot(dx, dy)
        da = (math.atan2(dy, dx) - ang + math.pi) % (2 * math.pi) - math.pi
        if dist < 1.6 and abs(da) < fov / 2: s.cover_f[j] = True

def draw_person(img, s, px, py, ang, cone=True, reach=2.6, fov=math.radians(60)):
    cx, cy = s.P(px, py)
    if cone:
        layer = Image.new('RGBA', img.size, (0, 0, 0, 0)); ld = ImageDraw.Draw(layer)
        pts = [(cx, cy)]
        for k in range(21):
            a = ang - fov / 2 + fov * k / 20
            pts.append((cx + math.cos(a) * reach * PX, cy + math.sin(a) * reach * PX))
        ld.polygon(pts, fill=CONE, outline=CONE_EDGE)
        img.alpha_composite(layer)
    d = ImageDraw.Draw(img)
    d.ellipse([cx - 16, cy - 16, cx + 16, cy + 16], fill=PERSON, outline=(0, 0, 0))
    # the phone, held out in front
    fx, fy = cx + math.cos(ang) * 22, cy + math.sin(ang) * 22
    nx, ny = -math.sin(ang) * 9, math.cos(ang) * 9
    d.polygon([(fx + nx, fy + ny), (fx - nx, fy - ny), (fx - nx + math.cos(ang) * 4, fy - ny + math.sin(ang) * 4), (fx + nx + math.cos(ang) * 4, fy + ny + math.sin(ang) * 4)], fill=PHONE)

def mark(d, ok, x, y, r=34):
    d.ellipse([x - r, y - r, x + r, y + r], fill=GREEN if ok else RED)
    if ok:
        d.line([(x - r * 0.45, y), (x - r * 0.1, y + r * 0.4), (x + r * 0.5, y - r * 0.4)], fill=(0, 0, 0), width=8)
    else:
        d.line([(x - r * 0.4, y - r * 0.4), (x + r * 0.4, y + r * 0.4)], fill=(255, 255, 255), width=8)
        d.line([(x - r * 0.4, y + r * 0.4), (x + r * 0.4, y - r * 0.4)], fill=(255, 255, 255), width=8)

def rec(d, t):
    if int(t * 2) % 2 == 0: d.ellipse([24, 24, 52, 52], fill=RED)
    d.text((62, 26), "REC", fill=TEXT)

def side_view(img, t):
    """Scene 1: holding the phone upright at chest height (then, briefly, how not to)."""
    d = ImageDraw.Draw(img)
    d.line([(0, 470), (W, 470)], fill=WALL, width=6)
    wrong = t > 3.8
    x0 = 330
    d.ellipse([x0 - 40, 120, x0 + 40, 200], fill=PERSON)              # head
    d.line([(x0, 200), (x0, 380)], fill=PERSON, width=22)             # body
    d.line([(x0, 380), (x0 - 50, 470)], fill=PERSON, width=18)        # legs
    d.line([(x0, 380), (x0 + 50, 470)], fill=PERSON, width=18)
    # arm and phone: upright at chest height, or tilted and low
    if not wrong:
        d.line([(x0, 240), (x0 + 110, 260)], fill=PERSON, width=18)
        d.rectangle([x0 + 110, 220, x0 + 132, 300], fill=PHONE, outline=(0, 0, 0))
        # what the camera sees: a level cone
        layer = Image.new('RGBA', img.size, (0, 0, 0, 0)); ld = ImageDraw.Draw(layer)
        ld.polygon([(x0 + 132, 260), (900, 130), (900, 390)], fill=CONE, outline=CONE_EDGE); img.alpha_composite(layer)
        d = ImageDraw.Draw(img); mark(d, True, 860, 80)
        d.line([(x0 - 120, 260), (x0 - 60, 260)], fill=WALL, width=3); d.text((x0 - 175, 252), "chest", fill=TEXT)
    else:
        d.line([(x0, 240), (x0 + 100, 330)], fill=PERSON, width=18)
        d.polygon([(x0 + 100, 320), (x0 + 170, 350), (x0 + 160, 372), (x0 + 90, 342)], fill=PHONE, outline=(0, 0, 0))
        layer = Image.new('RGBA', img.size, (0, 0, 0, 0)); ld = ImageDraw.Draw(layer)
        ld.polygon([(x0 + 165, 360), (700, 470), (420, 470)], fill=(255, 82, 82, 60), outline=(255, 82, 82, 160)); img.alpha_composite(layer)
        d = ImageDraw.Draw(img); mark(d, False, 860, 80)

def room_a():
    return Scene([((0, 0), (6, 0)), ((6, 0), (6, 3.6)), ((6, 3.6), (0, 3.6)), ((0, 3.6), (0, 0))],
                 furniture=[(3.6, 0.3, 1.4, 0.8, 'table')])

def scene_walk(img, s, t, cover=True):
    """Scene 2: walking sideways along the bottom wall, 1.5 m from it, phone facing the wall."""
    d = ImageDraw.Draw(img)
    u = ease(t / 6.5)
    px, py, ang = 0.6 + 4.8 * u, 3.6 - 1.5, math.pi / 2
    if cover and t <= 6.5: add_cover(s, px, py, ang)
    draw_room(d, s, img); draw_person(img, s, px, py, ang)
    d = ImageDraw.Draw(img)
    # the 1–2 m gap
    gx = s.P(px, py)[0] + 60
    d.line([(gx, s.P(0, py)[1]), (gx, s.P(0, 3.6)[1] - 5)], fill=TEXT, width=2); d.text((gx + 8, s.P(0, py + 0.6)[1]), "1-2 m", fill=TEXT)
    rec(d, t); mark(d, True, 900, 470)

def scene_spin(img, s, t):
    """Scene 2b: turning on the spot instead: the view sweeps but little gets a proper look."""
    d = ImageDraw.Draw(img)
    px, py = 3.0, 1.8; ang = math.pi / 2 + t * 2.2
    draw_room(d, s, img); draw_person(img, s, px, py, ang, reach=1.4)
    d = ImageDraw.Draw(img)
    # a curved arrow around the person
    cx, cy = s.P(px, py); r = 60
    d.arc([cx - r, cy - r, cx + r, cy + r], start=-20 + t * 120, end=200 + t * 120, fill=RED, width=6)
    rec(d, t); mark(d, False, 900, 470)

def scene_corners(img, s, t):
    """Scene 3: the corner and under the table get their own slow look."""
    d = ImageDraw.Draw(img)
    T = 6.0
    if t < 2.5:   # walk to the top-left corner, sweeping it
        u = ease(t / 2.5); px, py = 1.3, 1.3 - 0.0 * u
        ang = -math.pi * 0.75 + (math.pi / 2) * math.sin(u * math.pi)  # sweep across the corner
        reach = 2.4
    elif t < 4.5:  # walk over to the table
        u = ease((t - 2.5) / 2.0); px, py = 1.3 + 1.6 * u, 1.3 + 0.7 * u; ang = -math.pi / 4 + (math.pi / 4) * u; reach = 2.2
    else:          # look under the table: crouching, cone short and low
        u = ease((t - 4.5) / 1.5); px, py = 2.9, 2.0; ang = -math.pi / 3 - 0.5 * math.sin(u * math.pi); reach = 1.6
    add_cover(s, px, py, ang, reach=reach)
    draw_room(d, s, img); draw_person(img, s, px, py, ang, reach=reach)
    d = ImageDraw.Draw(img); rec(d, t); mark(d, True, 900, 470)

def rooms_b():
    # two rooms side by side with a door gap in the shared wall
    walls = [((0, 0), (7, 0)), ((7, 0), (7, 3.6)), ((7, 3.6), (0, 3.6)), ((0, 3.6), (0, 0)),
             ((3.5, 0), (3.5, 1.3)), ((3.5, 2.3), (3.5, 3.6))]
    return Scene(walls, origin=(130, 90))

def scene_door(img, s, t):
    """Scene 4: through the door into the next room without stopping the recording."""
    d = ImageDraw.Draw(img)
    u = ease(t / 5.0)
    px = 1.2 + 4.6 * u; py = 1.8; ang = 0 if u < 0.55 else (0 + (math.pi / 2) * ease((u - 0.55) / 0.45))
    add_cover(s, px, py, ang)
    draw_room(d, s, img)
    # the door leaf
    d.line([s.P(3.5, 1.3), s.P(3.5 + 0.9, 1.3 + 0.5)], fill=WALL, width=6)
    draw_person(img, s, px, py, ang)
    d = ImageDraw.Draw(img); rec(d, t); mark(d, True, 900, 470)

def scene_glass(img, s, t):
    """Scene 5: glass and mirrors mislead the camera; light helps."""
    d = ImageDraw.Draw(img)
    draw_room(d, s, img)
    win = (6.0, 0.6, 0.12, 1.6)  # a window in the right wall
    d.rectangle([s.P(win[0] - 0.06, win[1]), s.P(win[0] + win[2], win[1] + win[3])], fill=GLASS)
    px, py, ang = 4.4, 1.4, 0.0
    draw_person(img, s, px, py, ang, reach=1.8)
    d = ImageDraw.Draw(img)
    if t < 3.0:
        mark(d, False, s.P(6.0, 1.4)[0] + 60, s.P(6.0, 1.4)[1])
    else:
        # a lamp: light on
        lx, ly = s.P(2.0, 0.9); d.ellipse([lx - 26, ly - 26, lx + 26, ly + 26], fill=(255, 230, 120))
        for k in range(8):
            a = k * math.pi / 4; d.line([(lx + math.cos(a) * 36, ly + math.sin(a) * 36), (lx + math.cos(a) * 54, ly + math.sin(a) * 54)], fill=(255, 230, 120), width=5)
        mark(d, True, lx + 90, ly)
    rec(d, t)

def scene_save(img, t):
    """Scene 6: press the check, the model generates, the room appears in 3D."""
    d = ImageDraw.Draw(img)
    if t < 1.5:
        r = 60 + 12 * math.sin(min(t, 1.0) * math.pi)
        mark(d, True, W / 2, H / 2, r=int(r))
    elif t < 3.5:
        u = (t - 1.5) / 2.0
        d.rounded_rectangle([230, 250, 730, 290], radius=20, fill=(60, 70, 85))
        d.rounded_rectangle([230, 250, 230 + 500 * u, 290], radius=20, fill=GREEN)
        d.text((430, 300), "generating 3D", fill=TEXT)
    else:
        u = ease((t - 3.5) / 1.2)
        # an isometric box room, faces filling in
        cx, cy = W / 2, H / 2 + 20; a, b, c = 220, 130, 150 * u
        floor = [(cx, cy + b), (cx + a, cy), (cx, cy - b), (cx - a, cy)]
        d.polygon(floor, fill=(60, 120, 80), outline=WALL_DONE)
        d.polygon([(cx - a, cy), (cx, cy - b), (cx, cy - b - c), (cx - a, cy - c)], fill=(90, 170, 110), outline=WALL_DONE)
        d.polygon([(cx, cy - b), (cx + a, cy), (cx + a, cy - c), (cx, cy - b - c)], fill=(70, 140, 95), outline=WALL_DONE)
        if u >= 1: mark(d, True, 860, 80)

SCENES = [  # (seconds, function)
    (6.0, 'side'), (7.0, 'walk'), (4.5, 'spin'), (6.0, 'corners'), (5.0, 'door'), (5.5, 'glass'), (5.0, 'save'),
]

def main(out):
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    cmd = [ff, '-y', '-hide_banner', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}', '-r', str(FPS), '-i', '-',
           '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-profile:v', 'baseline', '-level', '3.1', '-crf', '27', '-preset', 'slow', '-movflags', '+faststart', out]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    room = room_a(); rooms = rooms_b(); glass_room = room_a()
    total = 0.0
    for secs, name in SCENES:
        frames = int(secs * FPS)
        for f in range(frames):
            t = f / FPS
            img = Image.new('RGBA', (W, H), BG + (255,))
            if name == 'side': side_view(img, t)
            elif name == 'walk': scene_walk(img, room, t)
            elif name == 'spin': scene_spin(img, room, t)
            elif name == 'corners': scene_corners(img, room, t)
            elif name == 'door': scene_door(img, rooms, t)
            elif name == 'glass': scene_glass(img, glass_room, t)
            elif name == 'save': scene_save(img, t)
            proc.stdin.write(img.convert('RGB').tobytes())
        total += secs
        print(f'{name}: ends at {total:.1f}s', flush=True)
    proc.stdin.close(); proc.wait()
    print('exit', proc.returncode)

if __name__ == '__main__':
    main(sys.argv[1])
