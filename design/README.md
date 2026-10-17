# NSND launcher icon — 2026-10-17

기존 눈·도로 로고를 바탕으로 글자를 제거하고 로고 전체를 중앙으로 옮겼다.
1:1 정사각형 파란 배경을 가장자리까지 채우고, 눈·도로 주변에는 충분한 배경 여백을 뒀다.

- `nsnd_launcher_v2_source.png`: 내장 이미지 생성 도구로 만든 새 원본(불투명 정사각형).
- `nsnd_launcher_master.png`: 앱 리소스 내보내기에 사용한 마스터.
- `nsnd_launcher_mask_preview.png`: 정사각형 원본, 원형 런처, 둥근 사각형 런처 미리보기.
- `../tools/generate_launcher_icons.py`: 밀도별 WebP·스플래시·미리보기 재생성.

런처 미리보기는 108dp 전체 그림 가운데 72dp 영역을 잘라서 마스크를 씌운다.
로고는 가운데 지름 66dp 안전 영역 안에 있으며, 단색 테마 아이콘도 축소·중앙 정렬했다.
단색은 기존 눈·도로 벡터를 사용한다. 색상 버전은 여백이 포함된 전체 비트맵을 사용한다.
스플래시 이미지에도 글자 없는 새 로고를 적용했다.

공식 기준: https://developer.android.com/develop/ui/compose/system/icon_design_adaptive

재생성(Pillow 필요):

```powershell
python tools/generate_launcher_icons.py design/nsnd_launcher_v2_source.png .
```

이미지 생성 방법: 내장 `image_gen` 도구. 최종 사용 프롬프트:

> Redraw this existing app icon as a polished final production icon, a full-bleed 1:1 square with fully opaque vivid blue gradient background covering ALL edges and corners. Preserve the original recognizable eye-and-perspective-road emblem in white, including the crescent inside the eye, the two tapered road edge ribbons and dashed centerline. Remove ALL lettering, especially NSND. Recenter the complete emblem vertically and horizontally together in the exact center of the square. CRUCIAL: make the emblem much smaller than in the reference: its entire width must be 50% of the canvas width and its entire height around 34%, with at least 25% clear blue margin to its left and right and 33% clear blue above and below. Keep ALL of the emblem within a circle of diameter 58% of canvas, centered at canvas center, so it cannot be clipped by an Android circular launcher mask. Maintain the original bright cyan-blue at upper left fading smoothly through vivid azure to deep royal blue at lower right. Clean smooth white shapes, simple elegant geometry, no transparency, no black, no texture, no grunge, no speckles, no shadows around the outside, no pre-rounded square corners, no frame, no letters, no words, no watermark. Show exactly ONE square icon only, not a mockup or grid. Negative space and generous blue background padding are essential.
