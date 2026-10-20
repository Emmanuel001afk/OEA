# EMAGE platform integration and launcher identity — 2026-10-20

## What was audited

EMAGE is OEA's real picture application, not only a page in the launcher. The
platform surface was compared with Android's own gallery and photo-picker
contracts:

- Android registers an app's capabilities from its manifest intent filters. A
  gallery uses `ACTION_MAIN` with `CATEGORY_APP_GALLERY`, and an image viewer
  needs `VIEW`/`EDIT` for `image/*`.
- A file-picker activity must return the selected URI to its caller for
  `ACTION_PICK` and `ACTION_GET_CONTENT`; it must not open a viewer and drop the
  caller's result.
- A receiving share target must accept both `ACTION_SEND` and
  `ACTION_SEND_MULTIPLE`, including `EXTRA_STREAM` and `ClipData`.
- Android 14 introduced selected-photos access. A gallery that requests a
  custom MediaStore UI should request `READ_MEDIA_VISUAL_USER_SELECTED` beside
  `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` and distinguish full, partial and
  denied access. A partial result is a working library, not an empty one.
- Android's Photo Picker is the privacy-preserving choice for one-off image
  selection. It does not need broad media permission, so EMAGE offers it from
  More options in addition to its whole-library mode.
- The default/system-gallery role is not a portable public role that every
  third-party launcher can claim. EMAGE advertises the stable intent category
  and lets Android's resolver/settings choose it; OEA does not pretend to hold
  an OEM-only role.

References:

- [Selected Photos Access](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)
- [Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)
- [`MediaStore.ACTION_PICK_IMAGES`](https://developer.android.com/reference/android/provider/MediaStore#ACTION_PICK_IMAGES)
- [Common intents and `CATEGORY_APP_GALLERY`](https://developer.android.com/reference/android/content/Intent#CATEGORY_APP_GALLERY)
- [Receiving simple data](https://developer.android.com/training/sharing/receive)
- [Android media access and default-gallery management](https://developer.android.com/training/data-storage/shared/media)

## Changes in this pass

### 1. Device and launcher identification

`OeaImageActivity` now:

- has `CATEGORY_APP_GALLERY` alongside `MAIN` and `LAUNCHER`, so Android and
  other launchers can identify EMAGE as a picture app;
- handles `VIEW`, `EDIT` and `ATTACH_DATA` image intents;
- handles `PICK` and `GET_CONTENT` as a real picker, returning one URI or a
  multi-selection with read grants;
- handles `SEND` and `SEND_MULTIPLE` image shares;
- is exported only for these declared contracts and carries every incoming URI
  grant through to the viewer; OEA's own FileProvider remains the safe provider
  used when EMAGE hands a picture back to another app.

OEA Home's first-run dock seeding also resolves `CATEGORY_APP_GALLERY` before
falling back to a generic `VIEW image/*` match. That keeps EMAGE visible as its
own launcher entry while respecting the user's selected external gallery when
one resolves first. Console › Device Info now reports both the resolved Home
role and the current image handler, so the device's actual selection is visible
instead of being guessed from OEA's settings.

### 2. Android 14 media access is honest

The manifest declares `READ_MEDIA_VISUAL_USER_SELECTED`. The shared
`OeaMediaLibrary` now records `FULL`, `PARTIAL` or `DENIED` access independently
for images, video and audio. EMAGE shows a visible limited-access banner and a
Manage access action instead of silently claiming it read the whole phone. The
permission request is version-correct:

- Android 14+: `READ_MEDIA_IMAGES` plus
  `READ_MEDIA_VISUAL_USER_SELECTED`;
- Android 13: `READ_MEDIA_IMAGES`;
- Android 10–12: `READ_EXTERNAL_STORAGE`;
- Android 9 and below: the legacy write permission is requested only where it
  exists and is needed.

VIP uses the same visual-media permission model for video. The MediaStore query
also excludes pending and trashed rows, so an in-progress camera write or a
system-trash item cannot appear as a broken picture.

### 3. Picker and share correctness

EMAGE's picker result path returns content URIs with `FLAG_GRANT_READ_URI_PERMISSION`
and `ClipData`. It handles the system Photo Picker on Android 13+ and an
`ACTION_OPEN_DOCUMENT` fallback on older devices, taking a persistable read
grant where the fallback supports one. Shared pictures are normalised from
`data`, `EXTRA_STREAM` and `ClipData` and open in the viewer without expanding
the sender's selection into the rest of the library.

EMAGE/VIP sharing now carries both `EXTRA_STREAM` and `ClipData`, which covers
older receivers and Android's newer URI-grant path. OEA's copy-to-MediaStore
and rotate-copy paths use `IS_PENDING` and clean up failed inserts, preventing
half-written files from being indexed by the launcher or another gallery.

## Verification checklist

1. Install OEA and check that EMAGE appears with its own icon and label in OEA
   Home, the drawer, Android launcher search, and Settings' app list.
2. From Files, Messages and another gallery, open a JPEG, PNG, HEIF and a
   `content://` URI with **Open with EMAGE**. Also test an `ACTION_SEND` share.
3. From a test app, launch `ACTION_PICK image/*` and
   `ACTION_GET_CONTENT image/*`, including `EXTRA_ALLOW_MULTIPLE`; confirm the
   caller receives the selected URI(s) and can read them after EMAGE closes.
4. On Android 14+, grant selected photos only, return to EMAGE, and verify the
   limited-access banner, visible selected pictures, and Manage access flow.
   Then switch to full access and confirm the banner disappears and album
   counts expand.
5. Revoke image access with OEA-owned camera pictures present. EMAGE should
   show those OEA pictures and explain that other albums are unavailable; it
   must not imply the library is complete.
6. Start a system Photo Picker selection from EMAGE More options and confirm it
   works without broad media access.
7. Capture a picture in OEA Camera while EMAGE is open. The content observer
   should update the library without reopening the activity. Kill a write in
   progress and confirm no pending row is displayed.
8. On a large screen, rotate and resize EMAGE, open a very large library, and
   verify the virtual grid remains responsive and the viewer keeps its URI
   grants. Device verification is still required because this checkout has no
   Android emulator or physical device.
