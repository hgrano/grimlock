// Copyright 2015,2016,2017,2018,2019 Commonwealth Bank of Australia
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package commbank.grimlock.test2

import commbank.grimlock.framework.encoding2._
import commbank.grimlock.test.TestGrimlock

class TestDoubleSchema extends TestGrimlock {
  "DoubleSchema" should "parse correctly" in {
    DoubleSchema.fromShortString("double") shouldBe Option(DoubleSchema())

    DoubleSchema.fromShortString("double(range(min=5))") shouldBe Option(DoubleSchema(RangeValidator(min = Option(5))))
  }
}

class TestStringSchema extends TestGrimlock {
  "StringSchema" should "parse correctly" in {
    StringSchema.fromShortString("string(boundedString(1, 10))") shouldBe Option(
      StringSchema(BoundedStringValidator(1, 10))
    )
  }
}
