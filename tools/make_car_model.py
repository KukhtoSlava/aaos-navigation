#!/usr/bin/env python3
"""Generates the low-poly car used as the location marker on the cluster map: a compact
crossover after the first-generation Hyundai Kona (2017-2023), white with a black roof.
The shape is recognisable by its split lights (thin DRL strips up at the hood line, lamps
low in the bumpers), black arch and sill cladding and roof rails. No badges or logos.

Writes app/src/main/assets/autobox_car.glb. Run it again after changing the shape or colours:

    python3 tools/make_car_model.py

The model follows the conventions of the TomTom SDK's own chevron.glb: glTF, Y up, the car
facing -Z, standing on y = 0 and centred on the origin, about two units long. Faces are flat
shaded (no shared vertices) for the crisp low-poly look.
"""

import json
import math
import os
import struct

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "autobox_car.glb")

# Materials: name -> (base colour RGBA, metallic, roughness, emissive RGB)
MATERIALS = {
    # White paint, lit a little so it reads on the dark map
    "paint": ((0.92, 0.93, 0.94, 1.0), 0.1, 0.3, (0.12, 0.12, 0.13)),
    "roof": ((0.03, 0.03, 0.035, 1.0), 0.3, 0.25, (0.0, 0.0, 0.0)),
    "glass": ((0.03, 0.05, 0.08, 1.0), 0.6, 0.15, (0.0, 0.0, 0.0)),
    "trim": ((0.05, 0.06, 0.07, 1.0), 0.2, 0.7, (0.0, 0.0, 0.0)),
    "tyre": ((0.04, 0.04, 0.045, 1.0), 0.0, 0.9, (0.0, 0.0, 0.0)),
    "rim": ((0.75, 0.78, 0.8, 1.0), 0.9, 0.3, (0.0, 0.0, 0.0)),
    "headlight": ((1.0, 1.0, 1.0, 1.0), 0.0, 0.2, (1.0, 0.97, 0.88)),
    "taillight": ((1.0, 0.1, 0.08, 1.0), 0.0, 0.3, (1.0, 0.08, 0.05)),
    "outline": ((0.02, 0.03, 0.04, 1.0), 0.0, 1.0, (0.0, 0.0, 0.0)),
    "shadow": ((0.0, 0.0, 0.0, 0.45), 0.0, 1.0, (0.0, 0.0, 0.0)),
}

# Car dimensions (units, -Z is the front): the Kona's 4.17 x 1.80 x 1.55 m scaled to 2.6 long
LENGTH = 2.6
HALF_W = 0.56
GROUND = 0.14  # underbody height: a crossover sits high
WHEEL_RADIUS = 0.21
WHEEL_Z = (-0.80, 0.82)


class Mesh:
    """Triangles per material. Each part sets [inside], a point within it: every face is
    wound to face away from that point, so the shapes below need not care about winding."""

    def __init__(self):
        self.parts = {name: ([], []) for name in MATERIALS}  # material -> (positions, normals)
        self.inside = (0.0, 0.0, 0.0)

    def tri(self, material, a, b, c):
        n = normal(a, b, c)
        centroid = tuple((a[i] + b[i] + c[i]) / 3 for i in range(3))
        if sum(n[i] * (centroid[i] - self.inside[i]) for i in range(3)) < 0:
            b, c = c, b
            n = tuple(-x for x in n)
        pos, nor = self.parts[material]
        for v in (a, b, c):
            pos.append(v)
            nor.append(n)

    def quad(self, material, a, b, c, d):
        self.tri(material, a, b, c)
        self.tri(material, a, c, d)

    def polygon(self, material, points):
        """Convex polygon."""
        for i in range(1, len(points) - 1):
            self.tri(material, points[0], points[i], points[i + 1])


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def normal(a, b, c):
    u, v = sub(b, a), sub(c, a)
    n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
    length = math.sqrt(sum(x * x for x in n)) or 1.0
    return tuple(x / length for x in n)


def extrude_profile(mesh, material, profile, half_width, side_material=None):
    """Extrudes a convex side profile [(z, y)] across x."""
    side_material = side_material or material
    right = [(half_width, y, z) for z, y in profile]
    left = [(-half_width, y, z) for z, y in profile]
    mesh.polygon(side_material, right[::-1])
    mesh.polygon(side_material, left)
    for i in range(len(profile)):
        j = (i + 1) % len(profile)
        mesh.quad(material, left[i], left[j], right[j], right[i])


def body(mesh):
    # Short overhangs, high bonnet, nearly flat beltline, upright tailgate
    profile = [
        (-1.30, GROUND + 0.02),
        (-1.30, 0.42),
        (-1.22, 0.56),
        (-0.55, 0.66),
        (0.95, 0.68),
        (1.22, 0.66),
        (1.30, 0.56),
        (1.30, GROUND + 0.03),
        (1.20, GROUND),
        (-1.20, GROUND),
    ]
    mesh.inside = (0.0, 0.4, 0.0)
    extrude_profile(mesh, "paint", profile, HALF_W)


def cabin(mesh):
    # Tapered glasshouse under a black roof with rails
    base_w, roof_w = HALF_W - 0.05, HALF_W - 0.14
    z0, z1, z2, z3 = -0.55, -0.12, 0.72, 0.95
    y0, y1 = 0.66, 0.97
    bl = [(-base_w, y0, z0), (base_w, y0, z0), (base_w, y0, z3), (-base_w, y0, z3)]
    tl = [(-roof_w, y1, z1), (roof_w, y1, z1), (roof_w, y1, z2), (-roof_w, y1, z2)]
    mesh.inside = (0.0, 0.8, 0.2)
    mesh.quad("glass", bl[0], bl[1], tl[1], tl[0])  # windscreen
    mesh.quad("glass", bl[1], bl[2], tl[2], tl[1])  # right windows
    mesh.quad("glass", bl[2], bl[3], tl[3], tl[2])  # rear window
    mesh.quad("glass", bl[3], bl[0], tl[0], tl[3])  # left windows
    lift = 0.025
    roof = [(x, y + lift, z) for x, y, z in tl]
    mesh.inside = (0.0, y1 - 0.5, 0.3)
    mesh.quad("roof", roof[3], roof[2], roof[1], roof[0])
    for i in range(4):
        j = (i + 1) % 4
        mesh.quad("roof", tl[i], tl[j], roof[j], roof[i])
    top = y1 + lift
    for side in (-1, 1):
        x0, x1 = sorted((side * (roof_w - 0.02), side * (roof_w - 0.07)))
        box(mesh, "trim", x0, x1, top, top + 0.035, z1 + 0.08, z2 - 0.06)  # roof rail


def cladding(mesh):
    """The black plastic that makes it a crossover: arches, sills and bumper bottoms."""
    flare = HALF_W + 0.035
    for side in (-1, 1):
        x0, x1 = sorted((side * (HALF_W - 0.04), side * flare))
        for z in WHEEL_Z:
            box(mesh, "trim", x0, x1, 0.30, 0.46, z - 0.30, z + 0.30)  # arch
        box(mesh, "trim", x0, x1, GROUND, GROUND + 0.1, WHEEL_Z[0] + 0.3, WHEEL_Z[1] - 0.3)  # sill
    box(mesh, "trim", -HALF_W - 0.01, HALF_W + 0.01, GROUND, GROUND + 0.12, -1.315, -1.12)
    box(mesh, "trim", -HALF_W - 0.01, HALF_W + 0.01, GROUND, GROUND + 0.13, 1.12, 1.315)


def wheel(mesh, x, z, radius=0.22, width=0.2, segments=14):
    cy = radius
    outer = 1 if x > 0 else -1
    mesh.inside = (x, cy, z)
    xi, xo = x - outer * width / 2, x + outer * width / 2
    ring = [(cy + radius * math.sin(a), z + radius * math.cos(a))
            for a in (2 * math.pi * k / segments for k in range(segments))]
    for k in range(segments):
        (y0, z0), (y1, z1) = ring[k], ring[(k + 1) % segments]
        a, b = (xi, y0, z0), (xi, y1, z1)
        c, d = (xo, y1, z1), (xo, y0, z0)
        mesh.quad("tyre", a, b, c, d)
    hub = [(xo, y, zz) for y, zz in ring]
    inner_hub = [(xi, y, zz) for y, zz in ring]
    rim = [(xo + outer * 0.005, cy + 0.6 * (y - cy), z + 0.6 * (zz - z)) for y, zz in ring]
    mesh.polygon("tyre", hub)
    mesh.polygon("tyre", inner_hub)
    mesh.polygon("rim", rim)


def box(mesh, material, x0, x1, y0, y1, z0, z1):
    mesh.inside = ((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
    p = [(x, y, z) for x in (x0, x1) for y in (y0, y1) for z in (z0, z1)]
    faces = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
    for f in faces:
        mesh.quad(material, *(p[i] for i in f))


def lights(mesh):
    # Split lights: thin strips up at the bonnet and tailgate line, lamps low in the bumpers
    for side in (-1, 1):
        def span(inner, outer):
            return sorted((side * inner, side * outer))
        box(mesh, "headlight", *span(0.16, 0.50), 0.505, 0.53, -1.275, -1.245)  # DRL strip
        box(mesh, "headlight", *span(0.30, 0.50), 0.31, 0.40, -1.318, -1.298)  # headlamp
        box(mesh, "taillight", *span(0.18, 0.50), 0.585, 0.615, 1.255, 1.285)  # tail strip
        box(mesh, "taillight", *span(0.32, 0.50), 0.30, 0.35, 1.30, 1.32)  # bumper lamp
    box(mesh, "trim", -0.27, 0.27, 0.22, 0.43, -1.318, -1.298)  # cascading grille


def shadow(mesh):
    mesh.inside = (0.0, -1.0, 0.0)
    # Outline: a thin dark rim just around the body; shadow: a soft wider plate under it
    for material, grow, y in (("outline", 0.05, 0.03), ("shadow", 0.16, 0.01)):
        w, l = HALF_W + grow, LENGTH / 2 + grow
        mesh.quad(material, (-w, y, l), (w, y, l), (w, y, -l), (-w, y, -l))


def build():
    mesh = Mesh()
    shadow(mesh)
    body(mesh)
    cabin(mesh)
    cladding(mesh)
    lights(mesh)
    for x in (-HALF_W + 0.07, HALF_W - 0.07):
        for z in WHEEL_Z:
            wheel(mesh, x, z, radius=WHEEL_RADIUS, width=0.18)
    return mesh


# The SDK's chevron.glb has three nodes, Base, Outline and Shadow, and the renderer looks
# them up by name. The car goes into Base; Outline and Shadow get the shadow plane.
NODES = (("Base", lambda m: m not in ("shadow", "outline")), ("Outline", lambda m: m == "outline"),
         ("Shadow", lambda m: m == "shadow"))


def write_glb(mesh, path):
    names = [n for n in MATERIALS if mesh.parts[n][0]]
    blob = bytearray()
    accessors, views, primitives = [], [], []
    for index, name in enumerate(names):
        positions, normals = mesh.parts[name]
        for data, kind in ((positions, "POSITION"), (normals, "NORMAL")):
            offset = len(blob)
            for v in data:
                blob += struct.pack("<3f", *v)
            views.append({"buffer": 0, "byteOffset": offset, "byteLength": len(blob) - offset, "target": 34962})
            accessor = {"bufferView": len(views) - 1, "componentType": 5126, "count": len(data), "type": "VEC3"}
            if kind == "POSITION":
                accessor["min"] = [min(v[i] for v in data) for i in range(3)]
                accessor["max"] = [max(v[i] for v in data) for i in range(3)]
            accessors.append(accessor)
        # Indexed, though trivially: TomTom's standard renderer refuses non-indexed meshes
        offset = len(blob)
        for i in range(len(positions)):
            blob += struct.pack("<H", i)
        blob += b"\0" * (-len(blob) % 4)
        views.append({"buffer": 0, "byteOffset": offset, "byteLength": 2 * len(positions), "target": 34963})
        accessors.append({"bufferView": len(views) - 1, "componentType": 5123, "count": len(positions), "type": "SCALAR"})
        primitives.append((name, {
            "attributes": {"POSITION": len(accessors) - 3, "NORMAL": len(accessors) - 2},
            "indices": len(accessors) - 1,
            "material": index,
        }))
    # One primitive per mesh, like chevron.glb. Base keeps a mesh per material as child nodes.
    nodes, meshes, children = [], [], []
    for node, belongs in NODES:
        nodes.append({"name": node})
    for m, primitive in primitives:
        meshes.append({"name": m, "primitives": [primitive]})
        owner = next(i for i, (_, belongs) in enumerate(NODES) if belongs(m))
        if NODES[owner][0] == "Base" and "mesh" in nodes[owner]:
            nodes.append({"name": "Base_" + m, "mesh": len(meshes) - 1})
            nodes[owner].setdefault("children", []).append(len(nodes) - 1)
        else:
            nodes[owner]["mesh"] = len(meshes) - 1
    materials = []
    for name in names:
        color, metallic, roughness, emissive = MATERIALS[name]
        material = {
            "name": name,
            # Like the SDK's chevron: the renderer then draws a face whatever its winding.
            "doubleSided": True,
            "pbrMetallicRoughness": {
                "baseColorFactor": list(color),
                "metallicFactor": metallic,
                "roughnessFactor": roughness,
            },
            "emissiveFactor": list(emissive),
        }
        if color[3] < 1.0:
            material["alphaMode"] = "BLEND"
        materials.append(material)
    gltf = {
        "asset": {"version": "2.0", "generator": "AutoBox make_car_model.py"},
        "scene": 0,
        "scenes": [{"nodes": list(range(len(NODES)))}],
        "nodes": nodes,
        "meshes": meshes,
        "materials": materials,
        "accessors": accessors,
        "bufferViews": views,
        "buffers": [{"byteLength": len(blob)}],
    }
    json_chunk = json.dumps(gltf, separators=(",", ":")).encode()
    json_chunk += b" " * (-len(json_chunk) % 4)
    blob += b"\0" * (-len(blob) % 4)
    total = 12 + 8 + len(json_chunk) + 8 + len(blob)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(struct.pack("<4sII", b"glTF", 2, total))
        f.write(struct.pack("<I4s", len(json_chunk), b"JSON") + json_chunk)
        f.write(struct.pack("<I4s", len(blob), b"BIN\0") + bytes(blob))


if __name__ == "__main__":
    write_glb(build(), OUT)
    print("wrote", os.path.normpath(OUT))
