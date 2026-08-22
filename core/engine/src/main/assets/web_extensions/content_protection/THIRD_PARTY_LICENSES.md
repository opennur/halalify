# Third-Party Content Protection Licenses

## `@vladmandic/human` 3.3.6

Copyright (c) Vladimir Mandic

Licensed under the MIT License:

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction,
> including without limitation the rights to use, copy, modify, merge, publish, distribute,
> sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or
> substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
> BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
> DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Model Credits

The bundled model weights are distributed by `@vladmandic/human` and are converted from the
following upstream models:

- MediaPipe BlazeFace: <https://github.com/google/mediapipe> (Apache-2.0)
- TensorFlow MoveNet MultiPose: <https://tfhub.dev/google/movenet/multipose/lightning/1> (Apache-2.0)
- HSE FaceRes: <https://github.com/HSE-asavchenko/HSE_FaceRec_tf> (Apache-2.0)

Human model credits and conversion notes: <https://github.com/vladmandic/human/wiki/Models>.
Shellify does not include or derive code from HaramBlur; the detector and regional overlay pipeline
are implemented independently.

## Apache License 2.0

The upstream model projects listed above are licensed under the Apache License, Version 2.0:

> Copyright 2019 The TensorFlow Authors. All rights reserved.
>
> Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
> in compliance with the License. You may obtain a copy of the License at
> <http://www.apache.org/licenses/LICENSE-2.0>.
>
> Unless required by applicable law or agreed to in writing, software distributed under the License
> is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
> or implied. See the License for the specific language governing permissions and limitations under
> the License.
