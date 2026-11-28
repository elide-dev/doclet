# Changelog

## [0.4.0](https://github.com/elide-dev/doclet/compare/v0.3.0...v0.4.0) (2026-11-28)


### Features

* advertise exact Markdown routes and evaluate HTML-start lookups with Luna ([e933eb6](https://github.com/elide-dev/doclet/commit/e933eb67788e1dfddb3b12006067b7413f58055c))
* advertise searchable qualified declaration contracts ([90747f3](https://github.com/elide-dev/doclet/commit/90747f3d6daed9c7ab74cb36852ddc20b6aad4a6))
* expose a searchable owner-qualified Markdown declaration index ([e5d527c](https://github.com/elide-dev/doclet/commit/e5d527c41ce73bb32fc7b48fe0f86a16089d31a4))
* link Markdown API summaries to exact overloads ([f2646f5](https://github.com/elide-dev/doclet/commit/f2646f5fa8b6daf98e423eec971db9939fcc468a))
* publish addressable member contracts and compare local-first readers ([98297b4](https://github.com/elide-dev/doclet/commit/98297b4405b3075a24c6c2f9af39cc89fc6c3ab2))
* read exact type documentation and retain missing contract evidence ([11f5829](https://github.com/elide-dev/doclet/commit/11f5829bd79b6b193a774e17a850afb818c36583))
* surface API contracts for agent discovery and short Markdown reads ([f006491](https://github.com/elide-dev/doclet/commit/f006491feedf8dc0b2c8eebd2b7ba31ff05f274c))


### Bug Fixes

* charge out-of-browser evaluation attempts as misses ([e645538](https://github.com/elide-dev/doclet/commit/e645538e201475d350868657ba6993da2469b6ba))
* distinguish behavior searches from exact declaration lookups ([94019a2](https://github.com/elide-dev/doclet/commit/94019a2c736236733abac0a2a8f1fbedc67af5cd))
* follow Markdown member links containing array signatures ([cfdb62a](https://github.com/elide-dev/doclet/commit/cfdb62ab13cc820708b7ef548975a7705a2b6876))
* follow public bundle URLs and recognize native Javadoc deprecations ([e5dced1](https://github.com/elide-dev/doclet/commit/e5dced133e07dd82da27656f53de079f9f7d1dc6))
* preserve overload lookup across qualified and legacy indexes ([529dcea](https://github.com/elide-dev/doclet/commit/529dcea8b578c99e54f1bf163ed03baf51010cb3))
* resolve aggregate Markdown links from the published bundle ([4a4581f](https://github.com/elide-dev/doclet/commit/4a4581f6e95e8aae5888d18405f6cd3aa23147a2))
* resolve linked overload summaries in local readers ([8b1476c](https://github.com/elide-dev/doclet/commit/8b1476c9a448e999feb66819e001a19304a40adb))
* verify complete fixture coverage in paired comparisons ([d498661](https://github.com/elide-dev/doclet/commit/d498661bf0293976712c9b370f5eb716da0dc2ee))


### Performance Improvements

* answer complete exception and return contracts directly ([19a3ea6](https://github.com/elide-dev/doclet/commit/19a3ea6377424c8e44f9ba8319cb1ee7c47d5ed0))
* cite a declaration by number for identifier questions ([f222ca6](https://github.com/elide-dev/doclet/commit/f222ca6f2a89d7ceeaad54fa4e38e3a88245ae83))
* distinguish return contracts and measure both member-index readers ([84d1f66](https://github.com/elide-dev/doclet/commit/84d1f66b2b9aee6a1a711d6fbf96d0ecc44577ed))
* index type contracts and resolve explicit overloads before ranking ([3b6e9aa](https://github.com/elide-dev/doclet/commit/3b6e9aa12d03f21f3670ef75f72840a62305e53c))
* retain conditional evidence and rank alternative declarations within one prompt ([72ca9b3](https://github.com/elide-dev/doclet/commit/72ca9b3923a5e8689d4a4ff56dd0bd960e7e418b))

## [0.3.0](https://github.com/elide-dev/doclet/compare/v0.2.0...v0.3.0) (2026-11-27)


### Features

* publish shared guide suites with release quality gates ([da17c4d](https://github.com/elide-dev/doclet/commit/da17c4da241d0d10e29baa0ca047e8daac2c6e6c))


### Bug Fixes

* **doclet:** report a malformed block tag instead of crashing ([a885615](https://github.com/elide-dev/doclet/commit/a885615b76a6f0bdba9717f3862df56d79741b27))
* **release:** resume tagged builds and read R2 objects by exact key ([5316bc0](https://github.com/elide-dev/doclet/commit/5316bc086b290c95d2831ef7811ceec7d8270bf2))

## [0.2.0](https://github.com/elide-dev/doclet/compare/v0.1.0...v0.2.0) (2026-11-26)


### Features

* publish Kotlin references and versioned documentation catalogs ([5ba7fd0](https://github.com/elide-dev/doclet/commit/5ba7fd06f64dd227b60aea47aaaaa72858ff5d53))


### Bug Fixes

* **release:** verify public plugins against empty consumer caches ([a4b2d20](https://github.com/elide-dev/doclet/commit/a4b2d2061c959901c49e9a5c5319fa12b5d9cb72))

## 0.1.0 (2026-11-26)


### Features

* add --problems to report comment problems as warnings ([eee9ce5](https://github.com/elide-dev/doclet/commit/eee9ce58b5f6013c8d39a2bcabec846aabd1e260))
* add --problems to report comment problems as warnings ([164bb52](https://github.com/elide-dev/doclet/commit/164bb528e42b02fd0501122eeb1133ce23ee1bfe)), closes [#4](https://github.com/elide-dev/doclet/issues/4)
* **doclet:** accept standard doclet options (-link, -linkoffline, -tag) ([003d169](https://github.com/elide-dev/doclet/commit/003d169e1eddd5296f51f1a2df7955e493141ff1))
* **doclet:** accept standard doclet options (-link, -linkoffline, -tag) ([124778a](https://github.com/elide-dev/doclet/commit/124778a6bdcea17882792b118a4b5fb7a776ab9a)), closes [#6](https://github.com/elide-dev/doclet/issues/6)
* **doclet:** render Markdown documentation comments ([dcc4ce6](https://github.com/elide-dev/doclet/commit/dcc4ce6152dd8a3d73d280bf06beffb6c61db76e))
* **doclet:** render Markdown documentation comments ([801e71e](https://github.com/elide-dev/doclet/commit/801e71e7c2cbe5f5d889a1a3b82ec3afd0a51118)), closes [#3](https://github.com/elide-dev/doclet/issues/3)
* initialize Elide doclet with build and release workflows ([8887f56](https://github.com/elide-dev/doclet/commit/8887f56c36fb0b7d089fe1405e183aecde5689a9))
* publish linked Markdown guides and lay out API signatures ([9f98a60](https://github.com/elide-dev/doclet/commit/9f98a608ed9e9d3358eac5fa01e23b2cc806a65a))
* render brandable API sites and publish a theme gallery ([a1468b3](https://github.com/elide-dev/doclet/commit/a1468b3cc40f07dc2ec11695f48483923e2ff0b9))
* **samples:** add Notflix and Rider themes with a live preview harness ([d875bdf](https://github.com/elide-dev/doclet/commit/d875bdf6d9e71c21175e467a1ebd094d21184c01))
* **samples:** draw parody wordmarks for Notflix and Rider ([556469d](https://github.com/elide-dev/doclet/commit/556469d796f8125db87336d2a8732977716ffa4a))
* **samples:** exercise the interactive reference across all themes ([44cf148](https://github.com/elide-dev/doclet/commit/44cf148c1b90b37125b230ce0f245a7f6ae5bad4))
* ship Gradle and Maven plugins around a shared generator core ([d263a9c](https://github.com/elide-dev/doclet/commit/d263a9c86a9484333e43cfb4dcea9949108b7715))
* **themes:** add Micronaut and Google samples ([d2f27ec](https://github.com/elide-dev/doclet/commit/d2f27ec79a8ec16305795077ec3480bb325d4ff3))


### Bug Fixes

* **ci:** scope label permissions to pull requests ([310be11](https://github.com/elide-dev/doclet/commit/310be11fc1053bef997679df36c4aac7c86f6127))
* **doclet:** inherit nothing from supertypes outside the documented set ([3e9edd9](https://github.com/elide-dev/doclet/commit/3e9edd941f4f0d1fbdc8fa7d0178d4ceb957a69e))
* **doclet:** inherit nothing from supertypes outside the documented set ([5608a6b](https://github.com/elide-dev/doclet/commit/5608a6bbc59b0bccacc32320a88154c0a2590c7f)), closes [#2](https://github.com/elide-dev/doclet/issues/2)
* **doclet:** treat Object's methods as inherited by interfaces ([88999a8](https://github.com/elide-dev/doclet/commit/88999a89d75a6cafeec095bb2cd4b7bba9c85543))
* **samples:** fit official GraalVM wordmarks to light and dark headers ([ac1a185](https://github.com/elide-dev/doclet/commit/ac1a185068d4a5a9046a277d44ac2676fc6403e4))
* **samples:** preserve SVG namespace separator in GraalVM logo ([bcfbbee](https://github.com/elide-dev/doclet/commit/bcfbbeeadb40a33c020fd396085eb46e1b6294a7))
* **samples:** size GraalVM header wordmark to the label cap height ([d2a14f8](https://github.com/elide-dev/doclet/commit/d2a14f8ad04fadc4d227e7389d1e70267594d4bb))


### Performance Improvements

* render the navigator once per site and make the extra outputs optional ([2c2c2df](https://github.com/elide-dev/doclet/commit/2c2c2dfd76fdb4c08715bb139eda8476f2073f75))
* render the navigator once per site and make the extra outputs optional ([5f7f904](https://github.com/elide-dev/doclet/commit/5f7f904e5f1091fa439d6bff0bd920fd9ce0e6ad)), closes [#5](https://github.com/elide-dev/doclet/issues/5)
