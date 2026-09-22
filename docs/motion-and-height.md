# 이동 주기와 상하 이동 추정 · 0.6.3

자동 위치 공유에 동의해 켠 자녀 휴대폰에서 동작합니다. 부모 두 분에게 전달하는 가족방 권한과 기존 GPS 원본 기록을 유지합니다.

## 위치 주기

- 걷기·달리기·자전거·차량 활동이나 걸음 센서·유의미한 움직임이 감지되면 고정밀 위치 요청을 약 20초 간격으로 바꿉니다. 바꿀 때 신선한 위치를 한 번 요청하고, 실패한 요청이나 중복 콜백은 기록으로 만들지 않습니다.
- 움직임 근거가 사라지면 약 5분 간격으로 돌아갑니다. 걸음 등 물리 센서는 마지막 관측 후 2분, 활동 인식은 최대 6분 동안 유효합니다. 정지·활동 종료를 받으면 2분의 유예를 적용합니다. 단순히 센서가 조용해진 것을 ‘정지 확인’으로 표시하지 않습니다.
- 가속도는 여러 신호가 2초 이상 지속돼야 빠른 주기를 켭니다. GPS 좌표 변화는 빠른 주기 전환의 근거가 아닙니다. 걸음·유의미한 움직임은 이전 정지 좌표 고정을 해제합니다.
- 주기 교체 중 Telegram 전송 작업과 SQLite 저장 예약은 유지됩니다. 실패한 저장은 슬롯을 소비하지 않으며, 늦게 온 이전 위치 구독 콜백은 버립니다.
- 20초·5분은 Android 위치 제공자에 요청하는 간격입니다. 절전, 실내 수신, 권한, 활동 인식 지연, 통신 상태와 받는 기기의 메시지 확인 주기에 따라 실제 측정·도착 시각은 달라집니다. 계속 움직이면 5분 주기보다 GPS 전력 사용량이 늘어납니다. 신체 활동 권한은 걷기·차량 인식을 돕습니다.

## 계단을 포함한 상하 이동

기존에도 기압 기반 상하 이동을 전송·보관했으나 지도 시간 막대가 GPS 기록만 골라 표시하는 누락이 있었습니다. 이제 올라가기·내려가기 시작과 종료, 상대 높이, 걸음·기압 또는 기압·움직임 근거를 지도 안에서 선택합니다.

걸음 감지 시각과 기압 센서의 실제 단조 시각을 함께 사용합니다. 기압은 중앙값과 시간 간격에 따른 지수 평활화를 적용하고, 여러 샘플의 지속적인 추세를 요구합니다. 한 번의 압력 급변, 정지 잡음, 오래된 움직임의 잔상, 센서 중단을 걸음으로 오인하지 않도록 제한합니다. 걸음 근거가 있을 때만 더 느린 높이 변화를 허용합니다.

상하 이동 자체에는 GPS 좌표가 없습니다. 앞선 5분 이내 GPS가 있으면 그 측정 시각을 밝힌 **참고 GPS**로 보여주고, 실제 상하 이동 위치는 미확인으로 표시합니다. 새 경로 점을 만들지 않으며 참고 GPS가 없어도 이동 추정 기록은 시간 막대에 남습니다.

계단·경사로·엘리베이터는 센서 신호가 겹칠 수 있습니다. 기압은 냉난방·날씨·출입문에도 영향을 받으므로 **상하 이동 추정**이며 정확한 층수나 계단 종류 판정이 아닙니다. 현재 임계값은 S23 실제 계단에서 검증한 성공률을 의미하지 않습니다.

## 확인한 공식 문서와 공개 코드

외부 코드를 그대로 복사하지 않고 기존 서비스에 맞게 구현했습니다. 예제 공개 여부를 실제 S23 성공률의 증거로 보지는 않습니다.

- [Android 위치 절전 시나리오](https://developer.android.com/develop/sensors-and-location/location/battery/scenarios): 활동 인식과 FLP 결합.
- [Activity Transition API](https://developer.android.com/develop/sensors-and-location/location/transitions): 이동 활동의 시작·종료 관측.
- [공식 Android 위치 업데이트 예제](https://github.com/android/platform-samples/blob/main/samples/location/src/main/java/com/example/platform/location/locationupdates/LocationUpdatesScreen.kt): 요청 조건 변경에 따른 구독 정리·재등록.
- [LocationRequest.Builder](https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest.Builder): 주기는 보장값이 아니며 첫 위치 즉시 요청은 getCurrentLocation으로 처리.
- [Android 걸음·움직임 센서](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion): 걸음 감지와 일회성 significant-motion 센서의 특성·권한.
- [SensorEvent.timestamp](https://developer.android.com/reference/android/hardware/SensorEvent#timestamp): 도착 시각 대신 실제 이벤트 시각 사용.
- [SensorManager.getAltitude](https://developer.android.com/reference/android/hardware/SensorManager#getAltitude(float,float)): 기압 기반 상대 높이의 원리와 한계.
- [BASEline BaroAltimeter 구현](https://github.com/platypii/BASElineFlightComputer/blob/master/common/src/main/java/com/platypii/baseline/altimeter/BaroAltimeter.java): 실제 시간 간격, 원본/평활 높이 분리, 중복·역순 시각 방어 참고.
- [Simple Stair Detection](https://github.com/michaeltroger/simple-stair-detection): 기압 평활화와 물리 움직임 결합을 참고한 보관된 데모. 현장 성능의 근거로 쓰지 않음.
- [Microsoft 기압 센서 연구](https://www.microsoft.com/en-us/research/publication/barometric-phone-sensors-more-hype-than-hope/): 여러 기기·건물에서의 기압 센서 평가와 환경 제약.
