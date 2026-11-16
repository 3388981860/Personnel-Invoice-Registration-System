# -*- coding: utf-8 -*-
"""验证码识别（自训练 ONNX 识别模型，配合 color_parse 使用）。"""
import json

from rapidocr import RapidOCR, EngineType


class CaptchaRecognizer:
    def __init__(self, model_path, dict_path):
        self.engine = RapidOCR(
            params={
                "Rec.model_path": model_path,
                "Rec.rec_keys_path": dict_path,
                "Rec.rec_img_shape": [3, 48, 128],
                "Rec.engine_type": EngineType.ONNXRUNTIME,
                "Rec.lang_type": "ch",
                "Global.text_score": 0.3,
            }
        )

    def infer(self, img_path):
        result = self.engine(img_path, use_det=False, use_cls=False)
        if result is None or result.txts is None or len(result.txts) == 0:
            return {"txts": "", "scores": 0.0}
        txts = list(result.txts)
        scores = [round(score, 4) for score in result.scores] if result.scores else [0.0]
        return {"txts": txts[0], "scores": scores[0]}

    def infer_json(self, img_path):
        return json.dumps(self.infer(img_path), ensure_ascii=False)
