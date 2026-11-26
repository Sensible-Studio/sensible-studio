---
title: Images and delivery
description: Renditions, image delivery by URL, presets, focus crops and auto tags for the media of Sensible Studio.
order: 60
---

An uploaded file is more than the file. Sensible Studio makes the sizes a site needs, can deliver any image at any size on request, finds the subject of a picture so crops keep it in view, and proposes tags by looking at the image.

## What happens to an uploaded file

| Kind of file | What is made |
|---|---|
| **Images** | Thumbnails, a web size and a zoom size. The image is never enlarged, and a photo taken sideways is turned upright |
| **Video** | A thumbnail, a poster frame, and a copy every browser can play. Duration, size and codecs are read |
| **PDF** | A preview of the first page. The text is taken out for the search |
| **Office documents** | A preview, and their text for the search |
| **SVG** | A preview. An SVG is always served in a way that keeps scripts inside it from running |

Video needs `ffmpeg` and office documents need LibreOffice on the machine that runs Sensible Studio. Without them those files are still stored and downloadable, but get no preview. See [Install and run](../getting-started/install.md).

**Reprocess files** makes everything again for a selection, for example after you changed a folder's settings.

## Processing profiles: renditions of your own

A processing profile is a list of extra renditions to make: a name, a size, a format (JPEG, PNG or WebP) and a quality. An administrator creates profiles under **Home › Media › Processing Profiles**; you choose one in a folder's properties. It applies to the folder, the folders below it, and the files uploaded or reprocessed from then on.

Use a profile for sizes that must exist as files, such as a print size someone downloads. For images shown on a website, use image delivery instead: nothing has to be made in advance.

## Image delivery: any size, by address

Every image in Media can be requested at any size, format and quality through its address. The image is made when it is first asked for, then kept.

```
/is/image/<the file's path below /content/dam>?<parameters>
```

For the file `/content/dam/weekender-shared/en/adventures/surf.jpg`, this address gives it 800 pixels wide, as WebP:

```
/is/image/weekender-shared/en/adventures/surf?wid=800&fmt=webp
```

| Parameter | Meaning |
|---|---|
| `wid`, `hei` | The box, in pixels. One of them alone keeps the proportions |
| `fit` | `fit` stays inside the box (the default); `crop` fills it and keeps the middle; `stretch` fills it without keeping proportions |
| `fmt` | `jpeg` (the default), `png`, `png-alpha`, `webp`, `webp-alpha` |
| `qlt` | Quality, 1 to 100 |
| `crop` | `x,y,w,h`: the part of the image to use |
| `rotate`, `flip` | `90`, `180`, `270`; `h`, `v`, `hv` |
| `op_sharpen`, `op_blur`, `op_brightness`, `op_contrast`, `op_saturation` | Adjustments |
| `op_grayscale=1`, `op_sepia=1` | Black and white; sepia |
| `op_gradient`, `op_overlay`, `op_vignette` | A fade, a tint, darker corners: useful under text |
| `op_radius`, `op_border` | Rounded corners; a border |
| `bgc` | The background colour where the image is transparent |

A file's page lists these addresses for you: open **Renditions**, and copy the one you need.

### Image presets

A preset is a named set of parameters, used as `?$card$`. Presets make every image of one kind come out the same, give you one place to change them all, and give people a word to use: "card" instead of `wid=800&hei=600&fit=crop`.

Sensible Studio starts with presets for common needs, among them `hero`, `banner`, `card`, `square`, `portrait`, `thumb`, `avatar`, `web`, `email` and `og` (for social cards).

An administrator manages them under **Home › Media › Image Presets**. The preset editor shows a real image delivered with exactly the settings you choose, with its size and weight, so you see what visitors will get.

## Focus crops: the subject stays in view

A site needs the same picture as a wide banner, a square and a tall portrait. A crop around the middle of the picture often cuts off what matters. A **focus crop** is placed around the subject instead.

1. An administrator creates an **image profile** under **Home › Media › Image Profiles**: a list of crops, each with a name and proportions, such as *Square* 1:1, *Banner* 16:5 and *Story* 9:16.
2. You choose the profile in a folder's properties.
3. Every image in the folder gets those crops, now and when new images arrive.

Request a crop by adding its name to the address:

```
/is/image/weekender-shared/en/adventures/surf:Story?wid=400&fmt=webp
```

### Adjust a crop

On a file's page, open **Renditions**. Each focus crop is listed with a preview. The pencil opens the crop editor: drag the frame to move it, or its corner to resize it; the proportions are kept. A crop you adjusted is yours and is not replaced when crops are computed again. **Reset to automatic** gives all of the file's crops back to the automatic choice.

### In pages

The **Responsive Image** component shows an image with one crop on wide screens and another on phones, each at the size the screen needs. Drag it onto a page, choose the image, and choose the crop for each screen size.

## Auto tags

Sensible Studio can look at an image and propose tags for it. It does this on its own machine: no picture is sent anywhere, and no account with an outside service is needed.

- **Set it up**: an administrator opens **Home › Media › Auto tags**, installs the image model once (a download of about 800 MB), and chooses which of your tags it may propose. Choose tags a picture can show: an activity or a season, not a region or a campaign.
- **Where it runs**: on the images of the folders where it is switched on, after they are uploaded. **Auto tags** on a selection or a folder runs it on images that are already there.
- **What you see**: in a file's properties, each proposal is shown with how sure the model is, such as "Surfing 93%". Accept one to make it a real tag; reject one and it is not proposed for that file again.

Proposed tags are found by the search even before anyone accepts them.

### Teach it your own tags

The model can learn what your tags mean from the images your team has already tagged. **Learn your tags**, on the Auto tags page, trains it on those examples. Proposals for those tags then follow your team's use of them.

### Colour tags

Each image also gets the colours it is made of, by name and by share. They show as swatches in its properties, and the search has a **Colour** filter: every image that is mostly blue, for example.

## Usage rights and watermarks

Under **Home › Media › Assets Configurations**, an administrator can:

- limit which kinds of files may be uploaded, and how large;
- protect files with rights (DRM): a file with a licence shows its terms, and must be accepted before it is downloaded;
- put a watermark on the previews of protected files;
- switch on the detection of duplicate files at upload.
