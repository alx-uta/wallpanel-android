/*
 * Copyright (c) 2022 WallPanel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.wallpanel.pro.network

import android.text.TextUtils
import xyz.wallpanel.pro.persistence.Configuration
import xyz.wallpanel.pro.utils.MqttUtils.Companion.TOPIC_COMMAND

import java.util.*
import javax.inject.Inject

class MQTTOptions @Inject
constructor(private val configuration: Configuration) {

    val brokerUrl: String
        get() = if (!TextUtils.isEmpty(getBroker())) {
            if (getBroker().contains("http://") || getBroker().contains("https://")) {
                String.format(Locale.getDefault(), HTTP_BROKER_URL_FORMAT, getBroker(), getPort())
            } else if (getTlsConnection()) {
                String.format(Locale.getDefault(), SSL_BROKER_URL_FORMAT, getBroker(), getPort())
            } else {
                String.format(Locale.getDefault(), TCP_BROKER_URL_FORMAT, getBroker(), getPort())
            }
        } else ""

    /**
     * Set on a client opened only to take the device out of Home Assistant after MQTT was
     * switched off, which has to reach the broker with the switch already off.
     */
    var connectWhileDisabled: Boolean = false

    val isValid: Boolean
        get() = isConfigured && (configuration.mqttEnabled || connectWhileDisabled)

    /** Whether there is enough to reach a broker with, whether or not MQTT is switched on. */
    val isConfigured: Boolean
        get() = if (getTlsConnection()) {
            !TextUtils.isEmpty(getBroker()) &&
                    !TextUtils.isEmpty(getClientId()) &&
                    !TextUtils.isEmpty(getBaseTopic()) &&
                    !TextUtils.isEmpty(getUsername()) &&
                    !TextUtils.isEmpty(getStateTopic()) &&
                    !TextUtils.isEmpty(getPassword())
        } else !TextUtils.isEmpty(getBroker()) &&
                !TextUtils.isEmpty(getStateTopic()) &&
                !TextUtils.isEmpty(getClientId())

    fun getVersion(): String {
        return configuration.mqttVersion
    }

    fun getBroker(): String {
        return configuration.mqttBroker
    }

    /**
     * Set on the client the settings screen opens to test a connection. It connects under
     * its own client id, since a broker drops whichever connection held an id when another
     * arrives with it, and that would be the device's own.
     */
    var connectionTest: Boolean = false

    fun getClientId(): String {
        val clientId = configuration.mqttClientId
        return if (connectionTest && clientId.isNotEmpty()) "${clientId}_test" else clientId
    }

    fun getBaseTopic(): String {
        return configuration.mqttBaseTopic
    }

    fun getStateTopic(): String {
        return getBaseTopic() + TOPIC_COMMAND
    }

    fun getStateTopics(): Array<String> {
        val topics = ArrayList<String>()
        topics.add(getStateTopic())
        return topics.toArray(arrayOf<String>())
    }

    fun getUsername(): String {
        return configuration.mqttUsername
    }

    fun getPassword(): String {
        return configuration.mqttPassword
    }

    fun getPort(): Int {
        return configuration.mqttServerPort
    }

    fun getTlsConnection(): Boolean {
        return configuration.mqttTlsEnabled
    }

    fun setTlsConnection(value: Boolean) {
        configuration.mqttTlsEnabled = value
    }

    companion object {
        const val SSL_BROKER_URL_FORMAT = "ssl://%s:%d"
        const val TCP_BROKER_URL_FORMAT = "tcp://%s:%d"
        const val HTTP_BROKER_URL_FORMAT = "%s:%d"
    }
}