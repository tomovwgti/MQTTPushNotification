# MQTT Push Notification sample

MQTTブローカに接続し、pushを受信するAndroidクライントのサンプルです。

[こちら](https://github.com/tokudu/AndroidPushNotificationsDemo)を参考にしてAndroid Studio(API Level19)でビルドできるように修正しています。

* ブローカサーバとトピックを設定すると、subscribeします
	* subscribeするトピックは１つだけですが、配列などで増やすのは難しくないでしょう
* publishがあるとpushが飛んできます。pushされる内容はペイロードです
* 待ち受けはserviceで行っているので、バックグラウンドで動作します

MQTTのpush待ち受けはかなり軽いですね。ちょっとしたサーバ監視や、センサーが動作したときのイベント通知などに使用できそうです。


### ブローカサーバ

動作確認のブローカサーバにはMosquittoを使いました。

[http://mosquitto.org/](http://mosquitto.org/)
