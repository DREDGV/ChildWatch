#!/usr/bin/env python3
"""Package approved original artwork; never fetch, invent or modify its content.

By default writes a reviewable .runtime catalog, leaving committed assets alone.
--publish is the explicit final packaging step. --animate explicitly opts into
gentle whole-image scale/rotation of approved hello/love poses, not new artwork.
Pillow is the only dependency. Source files are read-only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
from pathlib import Path

from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[1]
CATALOG=ROOT/"server/assets/chat-catalog/v1"
SIZE=256
MAX_BYTES=1024*1024


def normalize_png(path):
    with Image.open(path) as source:
        if source.format!="PNG" or source.width<128 or source.height<128:
            raise ValueError(f"Expected original PNG at least 128×128: {path.name}")
        if source.width*source.height>16_777_216:
            raise ValueError(f"Image exceeds 16 megapixels: {path.name}")
        source.load()
        rgba=source.convert("RGBA")
    alpha=rgba.getchannel("A").getextrema()
    if alpha[0]!=0 or alpha[1]==0:
        raise ValueError(f"Transparent background required: {path.name}")
    # Framing and encoder conversion only; no crop, retouch, repaint or filters.
    rgba.thumbnail((232,232),Image.Resampling.LANCZOS)
    result=Image.new("RGBA",(SIZE,SIZE))
    result.alpha_composite(rgba,((SIZE-rgba.width)//2,(SIZE-rgba.height)//2))
    return result


def motion_frames(image, kind):
    frames=[]
    for i in range(12):
        t=2*math.pi*i/12
        if kind=="hello-wave":
            frame=image.rotate(3*math.sin(t),resample=Image.Resampling.BICUBIC)
        else:
            scale=1+0.035*(1-math.cos(t))/2
            side=round(SIZE*scale)
            enlarged=image.resize((side,side),Image.Resampling.LANCZOS)
            frame=Image.new("RGBA",(SIZE,SIZE))
            frame.alpha_composite(enlarged,((SIZE-side)//2,(SIZE-side)//2))
        frames.append(frame)
    return frames


def write_gif(frames,path):
    palette_source=Image.new("RGB",(SIZE,SIZE*len(frames)),"white")
    for i,frame in enumerate(frames):
        opaque=Image.new("RGBA",frame.size,"white")
        opaque.alpha_composite(frame)
        palette_source.paste(opaque.convert("RGB"),(0,i*SIZE))
    palette=palette_source.quantize(colors=255,method=Image.Quantize.MEDIANCUT)
    indexed_frames=[]
    for frame in frames:
        opaque=Image.new("RGBA",frame.size,"white")
        opaque.alpha_composite(frame)
        indexed=opaque.convert("RGB").quantize(palette=palette,dither=Image.Dither.NONE)
        indexed.paste(255,mask=frame.getchannel("A").point(lambda alpha:255 if alpha<128 else 0))
        colors=indexed.getpalette()
        colors[765:768]=[255,255,255]
        indexed.putpalette(colors)
        indexed.info["transparency"]=255
        indexed_frames.append(indexed)
    indexed_frames[0].save(path,save_all=True,append_images=indexed_frames[1:],
                          loop=0,duration=100,disposal=2,transparency=255,optimize=False)


def validate(stage,items):
    for item in items:
        path=stage/item["filename"]
        content=path.read_bytes()
        if len(content)>MAX_BYTES:
            raise ValueError(f"Asset exceeds 1 MiB: {path.name}")
        with Image.open(path) as im:
            if im.size!=(SIZE,SIZE):
                raise ValueError(f"Incorrect dimensions: {path.name}")
            if item["type"]=="STICKER":
                if im.format!="PNG" or im.mode!="RGBA" or im.getchannel("A").getextrema()[0]!=0 or im.getchannel("A").getextrema()[1]==0:
                    raise ValueError(f"Incorrect sticker encoding: {path.name}")
            elif im.format!="GIF" or not 2<=im.n_frames<=24 or im.info.get("loop")!=0:
                raise ValueError(f"Expected genuine bounded animated GIF: {path.name}")
        item["sizeBytes"]=len(content)
        item["sha256"]=hashlib.sha256(content).hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir",type=Path,required=True,
                        help="12 approved ID.png files and optional 2 ID.gif files")
    parser.add_argument("--animate",action="store_true",
                        help="Explicitly create 12-frame subtle GIFs from approved hello/love PNGs")
    parser.add_argument("--publish",action="store_true",
                        help="Copy fully validated staged bytes and catalog into version 1")
    args=parser.parse_args()
    source=args.input_dir.resolve()
    if not source.is_dir():
        raise ValueError("Input directory does not exist")
    catalog=json.loads((CATALOG/"catalog.json").read_text(encoding="utf-8"))
    items=catalog["items"]
    if catalog["version"]!=1 or len(items)!=14 or len({i["id"] for i in items})!=14:
        raise ValueError("Expected unchanged 14-item version 1 contract")
    stage=ROOT/".runtime/chat-catalog-modern-stage"
    stage.mkdir(parents=True,exist_ok=True)
    images={}
    source_hashes={}
    for item in items:
        if item["type"]!="STICKER":
            continue
        path=source/item["filename"]
        image=normalize_png(path)
        images[item["id"]]=image
        image.save(stage/item["filename"],optimize=True)
        source_hashes[item["id"]]=hashlib.sha256(path.read_bytes()).hexdigest()
    for item in items:
        if item["type"]!="GIF":
            continue
        if args.animate:
            base=images["hello" if item["id"]=="hello-wave" else "love"]
            write_gif(motion_frames(base,item["id"]),stage/item["filename"])
        else:
            shutil.copyfile(source/item["filename"],stage/item["filename"])
    validate(stage,items)
    (stage/"catalog.json").write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    sheet=Image.new("RGB",(4*SIZE,4*278),"#F6FAF9")
    draw=ImageDraw.Draw(sheet)
    for index,item in enumerate(items):
        with Image.open(stage/item["filename"]) as encoded:
            image=encoded.convert("RGBA")
        x=(index%4)*SIZE
        y=(index//4)*278
        sheet.paste(image,(x,y),image)
        draw.text((x+12,y+255),item["id"],fill="#171D1C")
    sheet.save(ROOT/".runtime/chat-catalog-modern-preview.png")
    proof={"status":"PASS","published":args.publish,"sourceSha256":source_hashes,
           "generatedMotion":args.animate,"assetBytes":sum(i["sizeBytes"] for i in items)}
    if args.publish:
        for item in items:
            shutil.copyfile(stage/item["filename"],CATALOG/item["filename"])
        # Manifest goes last, after all validated bytes; sources remain untouched.
        shutil.copyfile(stage/"catalog.json",CATALOG/"catalog.json")
    (ROOT/".runtime/chat-catalog-modern-import.json").write_text(json.dumps(proof,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"status":"PASS","staged":str(stage),"published":args.publish,
                      "assetCount":len(items),"bytes":proof["assetBytes"]}))


if __name__=="__main__":
    main()
