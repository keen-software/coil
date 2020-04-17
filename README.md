﻿![Coil](logo.svg)

An image loading library for Android backed by Kotlin Coroutines. Coil is:

- **Fast**: Coil performs a number of optimizations including memory and disk caching, downsampling the image in memory, re-using Bitmaps, automatically pausing/cancelling requests, and more.
- **Lightweight**: Coil adds ~1500 methods to your APK (for apps that already use OkHttp and Coroutines), which is comparable to Picasso and significantly less than Glide and Fresco.
- **Easy to use**: Coil's API leverages Kotlin's language features for simplicity and minimal boilerplate.
- **Modern**: Coil is Kotlin-first and uses modern libraries including Coroutines, OkHttp, Okio, and AndroidX Lifecycles.

Coil is an acronym for: **Co**routine **I**mage **L**oader.

Made with ❤️ at [Instacart](https://www.instacart.com). Translations: [한국어](README-ko.md)

## Download

Coil is available on `mavenCentral()`.

```kotlin
implementation("io.coil-kt:coil:0.9.5")
```

## Quick Start

To load an image into an `ImageView`, use the `load` extension function:

```kotlin
// URL
imageView.load("https://www.example.com/image.jpg")

// Resource
imageView.load(R.drawable.image)

// File
imageView.load(File("/path/to/image.jpg"))

// And more...
```

Requests can be configured with an optional trailing lambda:

```kotlin
imageView.load("https://www.example.com/image.jpg") {
    crossfade(true)
    placeholder(R.drawable.image)
    transformations(CircleCropTransformation())
}
```

To load an image into a custom target, execute a `LoadRequest`:

```kotlin
val request = LoadRequest.Builder(context)
    .data("https://www.example.com/image.jpg")
    .target { drawable ->
        // Handle the result.
    }
    .build()
Coil.execute(request)
```

To get an image imperatively, execute a `GetRequest`:

```kotlin
val request = GetRequest.Builder(context)
    .data("https://www.example.com/image.jpg")
    .build()
val drawable = Coil.execute(request).drawable
```

The above examples use `io.coil-kt:coil`, which contains the `Coil` singleton. Optionally, you can depend on `io.coil-kt:coil-base` instead and inject your own [`ImageLoader`](https://coil-kt.github.io/coil/image_loaders/) instance(s).

Coil requires [Java 8 bytecode](https://coil-kt.github.io/coil/getting_started/#java-8). Check out Coil's [full documentation here](https://coil-kt.github.io/coil/).

## Requirements

- AndroidX
- Min SDK 14+
- Compile SDK: 29+
- Java 8+

## R8 / Proguard

Coil is fully compatible with R8 out of the box and doesn't require adding any extra rules.

If you use Proguard, you may need to add rules for [Coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/jvm/resources/META-INF/proguard/coroutines.pro), [OkHttp](https://github.com/square/okhttp/blob/master/okhttp/src/main/resources/META-INF/proguard/okhttp3.pro) and [Okio](https://github.com/square/okio/blob/master/okio/src/jvmMain/resources/META-INF/proguard/okio.pro).

## License

    Copyright 2020 Coil Contributors

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
