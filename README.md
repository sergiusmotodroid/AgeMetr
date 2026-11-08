# AgeMetr

The app opens a live front-camera preview and estimates the largest visible face about once per second. A face-shaped guide helps position the face. Selecting an existing photo is an optional alternative.

Age and gender inference run locally on the device. The models are bundled with the app; no model download is needed at first launch.

## Authors and licenses

- App code: sergius.
- Age and gender model: [MiVOLO v2](https://huggingface.co/iitolstykh/mivolo_v2) by Maksim Kuprashevich, Grigorii Alekseenko and Irina Tolstykh, licensed under [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/). The bundled model was converted to ONNX and quantized to int8 for AgeMetr.
- **Non-commercial use only**, because of the model license.

Full list of third-party components and their licenses: [NOTICE.md](NOTICE.md). Model details: `app/src/main/assets/MODEL_NOTICE.txt`.
