# 2M Memo

2M Memo is an infinite-canvas note app focused on handwriting and mind maps.

The current version is an Android prototype. The long-term direction is to provide a desktop web editor while keeping user data in the user's own storage, such as Google Drive or OneDrive.

## Current Features

- Infinite canvas with grid, zoom, pan, rotation, and rotation lock
- Pen tools with several writing styles and soft writing sound
- Eraser with adjustable size, full handwriting clear, and mind-map box deletion
- Mind map nodes, arrows, folding, resizing, themes, and templates
- In-app save box with folders and memo names
- In-app help document opened from the `?` button
- Mini map, zoom percentage display, center guide, and one-click return to center

## Repository Structure

```text
2m-memo/
  android/   Android app source
  web/       Future Netlify web editor
  docs/      Product notes and usage docs
```

## Android Build

```powershell
cd android
gradle.bat assembleDebug
```

The generated APK is intentionally not committed. Release APKs should be attached to GitHub Releases.

## Data Direction

The planned shared file format is a user-owned memo file, such as `.2memo`, containing canvas strokes, mind-map nodes, arrows, viewport state, themes, and metadata as JSON.

For the first web version, the recommended workflow is:

1. Open a memo file in the browser.
2. Edit it on the PC.
3. Save the file back to the user's own drive.
4. Reopen the same file on Android.

