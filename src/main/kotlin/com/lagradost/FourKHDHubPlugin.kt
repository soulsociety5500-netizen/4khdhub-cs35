
name: Build & Deploy CloudStream Extension

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout repo
        uses: actions/checkout@v3

      - name: Clone CS3 plugin template
        run: |
          git clone https://github.com/recloudstream/cloudstream-extensions-template cs3-template

      - name: Copy extension into template
        run: |
          mkdir -p cs3-template/FourKHDHub/src/main/kotlin/com/lagradost
          cp src/main/kotlin/com/lagradost/*.kt cs3-template/FourKHDHub/src/main/kotlin/com/lagradost/
          cp build.gradle.kts cs3-template/FourKHDHub/build.gradle.kts

      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Build .cs3 plugin
        working-directory: cs3-template
        run: |
          chmod +x gradlew
          ./gradlew :FourKHDHub:make

      - name: Collect build output
        run: |
          mkdir -p dist
          cp cs3-template/FourKHDHub/build/*.cs3 dist/FourKHDHubPlugin.cs3
          cp plugins.json dist/plugins.json
          cp repo.json dist/repo.json

      - name: Deploy to GitHub Pages
        uses: peaceiris/actions-gh-pages@v3
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: ./dist
          publish_branch: gh-pages
