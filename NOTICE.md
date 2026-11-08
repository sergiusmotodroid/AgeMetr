# Third-party notices

AgeMetr is built on the following third-party components.

## Model

| Component | Authors | License |
|---|---|---|
| [MiVOLO v2](https://huggingface.co/iitolstykh/mivolo_v2) (`app/src/main/assets/mivolo_face_int8.onnx`) | Maksim Kuprashevich, Grigorii Alekseenko, Irina Tolstykh | [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/), non-commercial use only |

The bundled file is a **modified** version of the original model. It was converted to ONNX (opset 18) with the body input fixed to empty, so only the face is used, and its MatMul weights were quantized to int8. The original authors do not endorse AgeMetr or these changes. Full details are in [`app/src/main/assets/MODEL_NOTICE.txt`](app/src/main/assets/MODEL_NOTICE.txt).

Because of the model license, **AgeMetr as distributed with this model may not be used for commercial purposes.**

Citations:

```bibtex
@article{mivolo2023,
  author  = {Kuprashevich, Maksim and Tolstykh, Irina},
  title   = {MiVOLO: Multi-input Transformer for Age and Gender Estimation},
  year    = {2023},
  journal = {arXiv preprint arXiv:2307.04616}
}

@article{mivolo2024,
  author  = {Kuprashevich, Maksim and Alekseenko, Grigorii and Tolstykh, Irina},
  title   = {Beyond Specialization: Assessing the Capabilities of MLLMs in Age and Gender Estimation},
  year    = {2024},
  journal = {arXiv preprint arXiv:2403.02302}
}
```

## Libraries

| Library | Author | License |
|---|---|---|
| [ONNX Runtime](https://github.com/microsoft/onnxruntime) | Microsoft | MIT |
| [ML Kit Face Detection](https://developers.google.com/ml-kit/vision/face-detection) | Google | [ML Kit Terms](https://developers.google.com/ml-kit/terms) |
| AndroidX (AppCompat, Core, Activity, CameraX) | Google / Android Open Source Project | Apache License 2.0 |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Google | Apache License 2.0 |
| Kotlin standard library | JetBrains | Apache License 2.0 |
