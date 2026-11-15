# MusicPaper

Android app that sets your lock screen wallpaper to the cover of the song that's playing.
It works with Spotify, YouTube, YouTube Music and local music players: any app that publishes a media session.

## Default wallpaper
Pick an image under **Default wallpaper**. When the music stops (the player is stopped or closed, not just paused),
the lock screen goes back to that image. If you haven't picked one, the lock screen goes back to matching your home screen.
The app saves its own copy of the image, so deleting the original photo doesn't break it.

## Layout
Pick one under **Style → Layout**; each option shows a preview made from the song that's playing:
- **Current**: solid colour above the cover (or the cover cropped to fill, with **Fill the screen**).
- **Square**: the whole cover as a centred square over a blurred copy of itself.

## Upscaling
Players often hand over small covers (300–640px), so the cover is enlarged to fit the screen. Pick one under **Style → Upscaling**:
- **Simple**: Lanczos resampling. Fast, light on battery.
- **Advanced**: [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) (`realesr-general-x4v3`, BSD-3-Clause) runs on the phone,
  on the GPU when it can. The simple version shows first and the sharp one replaces it a few seconds later.
  Results are cached, so a song you replay is instant. Nothing leaves the phone.

The model (`app/src/main/assets/upscaler_x4.tflite`) is built with `tools/convert_upscaler.py`; see that file for the steps.

## Permissions
| Permission | How it's granted |
|---|---|
| `SET_WALLPAPER` | Granted when you install the app. Android shows no dialog for it. |
| Notification access | You turn it on in system settings (tap **Grant** in the app). The app only reads media from the apps you tick in its list. |
| `INTERNET` | Granted when you install. It's only used when a player gives a cover URL instead of an image. |

## Build and install
```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```
On Android 13+, sideloaded apps can't get notification access until you allow it:
**Settings → Apps → MusicPaper → ⋮ → Allow restricted settings**.
