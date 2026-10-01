# Hoffice (doc-viewer)

안드로이드용 오프라인 문서뷰어. 인터넷 없이 폰 안에서 아래 형식을 엽니다.

- 한글: HWP, HWPX (비밀번호 문서 포함)
- 워드·엑셀·파워포인트: DOC, DOCX, XLS, XLSX, PPT, PPTX (옛날 형식 포함)
- PDF, ODF, RTF, CSV, TXT, 그림

기능:
- 카톡·메일 첨부 바로 열기, ZIP 첨부 안의 문서 열기
- 문서 안 글자 찾기, 최근 문서를 내용으로 찾기, 쪽 이동, 목차, 쪽 한눈에 보기, 읽던 자리 이어 보기
- 원본·PDF 공유, 이 쪽을 그림으로 공유, 폰에 저장, 인쇄
- 엑셀 시트 탭, PPT 슬라이드쇼, 워드 메모 표시, PDF 링크, 글자 선택·복사
- 야간 모드, 두 번 탭 확대, 읽어 주기(TTS), 즐겨찾기·종류별 보기

처음 한 번 사용 승인(lic.hyt.kr)을 받으면 이후에는 인터넷 없이 동작합니다. 문서는 폰 밖으로 나가지 않습니다.

## 빌드

```bash
./scripts/fetch-libreoffice.sh
./gradlew assembleRelease
```

## 사용한 오픈소스

- [rhwp](https://github.com/edwardkim/rhwp) (MIT) — HWP/HWPX 렌더링. WMF 차트 수정 패치는 `patches/`
- [LibreOffice](https://www.libreoffice.org/) (MPL-2.0) — 오피스 문서 엔진 (F-Droid LibreOffice Viewer 빌드의 네이티브 라이브러리)
- [pdf.js](https://github.com/mozilla/pdf.js) (Apache-2.0) — PDF 표시

본 제품은 한글과컴퓨터의 한글 문서 파일(.hwp) 공개 문서를 참고하여 개발하였습니다.
