# 목적지 보내기
- 대상: 출발 전 휴대폰 사용자와 차 안 거치 태블릿 사용자.
- 흐름: 검색 → 주소 확인·선택 → 유효시간 → 전송 → 서버 처리 상태.
- 기존 DESIGN.md의 파랑/회색 토큰, 둥근 카드, 한 열 스크롤을 유지한다.
- 첫 화면은 보내기에 집중하고 기기 연결·받기 설정은 별도 화면 상태로 접는다.
- 임시 폰 한 대 테스트는 전송/수신 버튼을 분리하고 실사용 설정을 바꾸지 않는다.
- 빈 검색, 선택 전, 연결 전, 처리 중, 오류, 만료, 확인 불가를 구별한다.
- 네이버지도 호출 성공은 경로 안내 시작 확인으로 표현하지 않는다.
- 검증: Paparazzi 휴대폰/태블릿, 낮/밤, 큰 글자. 실제 결과를 내부 열람한다.

## Direction contract
THESIS: 출발 전 확정한 한 장소를 유효시간 안에 차로 전달한다. 검색 선택과 전송 결과를 분리한다.
OWN-WORLD: 기존 Smart Tesla의 T/Space/Radius, 파랑 버튼과 회색 배경·둥근 흰 카드. 새 디자인 체계를 만들지 않는다.
STORY: 주소를 확인하고 전송하면 수신 대기를 본다. 별도 연결 화면에서 폰과 태블릿 역할을 구분한다.
FIRST VIEWPORT: 뒤로와 제목, 요청이 있으면 현재 상태, 검색 입력 순서. 한 열 스크롤, 넓은 화면 최대 640dp. 선택 후 전송 버튼이 활성화된다.
FORM: 기존 제품 화면 확장. 코드 중심의 한 열 작업 화면으로 사용자가 승인한 흐름을 구현한다. 기존 체계 확장이므로 무작위 seed 적용 안 함.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
