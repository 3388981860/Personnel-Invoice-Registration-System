# -*- coding: utf-8 -*-
"""验证码按颜色分离（查验平台验证码是彩色字符，先抠出指定颜色再识别）。"""
from PIL import Image
from collections import defaultdict


class ColorParser:
    COLOR_BLUE = '03'
    COLOR_RED = '01'
    COLOR_YELLOW = '02'
    COLOR_BLACK = '00'

    @staticmethod
    def is_text_red(r, g, b):
        return r > g + 80 and r > b + 80 and r > 100

    @staticmethod
    def is_text_yellow(r, g, b):
        return r > b + 80 and g > b + 80 and r > 100 and g > 100

    @staticmethod
    def is_text_blue(r, g, b):
        return b > r + 80 and b > g + 80 and b > 100

    @staticmethod
    def is_text_black(r, g, b):
        return r < 80 and g < 80 and b < 80

    def get_most_common_color(self, img):
        img = img.convert("RGB")
        pixdata = img.load()
        color_counts = defaultdict(int)
        width, height = img.size

        for y in range(height):
            for x in range(width):
                color = pixdata[x, y]
                color_counts[color] += 1

        white = (255, 255, 255)
        if white in color_counts:
            del color_counts[white]

        if not color_counts:
            return None

        return max(color_counts, key=color_counts.get)

    def remove_background_color(self, img, bg_color, tolerance=30):
        img_copy = img.copy().convert("RGB")
        pixdata = img_copy.load()
        width, height = img_copy.size
        br, bg, bb = bg_color

        for y in range(height):
            for x in range(width):
                r, g, b = pixdata[x, y]
                if abs(r - br) < tolerance and abs(g - bg) < tolerance and abs(b - bb) < tolerance:
                    pixdata[x, y] = (255, 255, 255)

        return img_copy

    def filter_by_color(self, img, color_type):
        img_copy = img.copy().convert("RGB")
        pixdata = img_copy.load()
        for y in range(img_copy.size[1]):
            for x in range(img_copy.size[0]):
                r, g, b = pixdata[x, y]
                if color_type == self.COLOR_RED:
                    keep = self.is_text_red(r, g, b)
                elif color_type == self.COLOR_YELLOW:
                    keep = self.is_text_yellow(r, g, b)
                elif color_type == self.COLOR_BLUE:
                    keep = self.is_text_blue(r, g, b)
                elif color_type == self.COLOR_BLACK:
                    keep = self.is_text_black(r, g, b)
                else:
                    raise ValueError("Unknown color_type: %s" % color_type)
                pixdata[x, y] = (0, 0, 0) if keep else (255, 255, 255)
        return img_copy
