/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.http2.{SessionCore, SessionFlowControlImpl, StreamFlowWindow}

/** Extends the [[SessionFlowControlImpl]] class but makes a couple critical methods no-ops */
private[http2] class ObservingSessionFlowControl(
    session: SessionCore
) extends SessionFlowControlImpl(
      session = session,
      flowStrategy = null /* only used on two overridden methods */
    ) {
  override protected def onSessionBytesConsumed(consumed: Int): Unit = ()
  override protected def onStreamBytesConsumed(stream: StreamFlowWindow, consumed: Int): Unit = ()
}
