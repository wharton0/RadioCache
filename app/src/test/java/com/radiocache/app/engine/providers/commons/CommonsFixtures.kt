package com.radiocache.app.engine.providers.commons

import com.radiocache.app.data.model.Show

/**
 * Captured Wikimedia Commons API responses, trimmed to the fields the parser
 * reads. Field values are taken verbatim from live `commons.wikimedia.org`
 * replies so the parser is verified without network access.
 */
object CommonsFixtures {

    /** Three real radio recordings: PD, PD-dedication, and CC BY 3.0. */
    val RADIO_SHOWS = """
    {
      "batchcomplete": "",
      "query": {
        "pages": [
          {
            "pageid": 1,
            "ns": 6,
            "title": "File:Jackie Gleason Les Tremayne NBC Radio Show 8-13-1944.ogg",
            "imageinfo": [
              {
                "url": "https://upload.wikimedia.org/wikipedia/commons/4/40/Jackie-Gleason_Les_Tremayne.ogg",
                "size": 21277453,
                "mime": "application/ogg",
                "extmetadata": {
                  "LicenseShortName": { "value": "Public domain" },
                  "UsageTerms": { "value": "Public domain" },
                  "Artist": { "value": "" },
                  "ImageDescription": { "value": "NBC Radio Show, 13 August 1944" },
                  "DateTimeOriginal": { "value": "1944-08-13 00:00:00" }
                }
              }
            ]
          },
          {
            "pageid": 2,
            "ns": 6,
            "title": "File:Sheffield Indymedia - 2010-06-16 Sheffield Indy Radio Show -1.vorb.oga",
            "imageinfo": [
              {
                "url": "https://upload.wikimedia.org/wikipedia/commons/0/07/Sheffield_Indymedia.oga",
                "size": 64800000,
                "mime": "application/ogg",
                "extmetadata": {
                  "LicenseShortName": { "value": "Public Domain" },
                  "UsageTerms": { "value": "Creative Commons Public Domain Dedication" },
                  "Artist": { "value": "Sheffield Indymedia" },
                  "ImageDescription": { "value": "Indymedia radio show recording" },
                  "Duration": { "value": "3900" }
                }
              }
            ]
          },
          {
            "pageid": 3,
            "ns": 6,
            "title": "File:Talk Nation Radio - 2011-10-06 David Swanson.oga",
            "imageinfo": [
              {
                "url": "https://upload.wikimedia.org/wikipedia/commons/4/4f/Talk_Nation_Radio.oga",
                "size": 13400000,
                "mime": "application/ogg",
                "extmetadata": {
                  "LicenseShortName": { "value": "CC BY 3.0" },
                  "UsageTerms": { "value": "Creative Commons Attribution 3.0" },
                  "Artist": { "value": "Progressive Media Project" },
                  "ImageDescription": { "value": "Talk Nation Radio broadcast" },
                  "Duration": { "value": "820.5" }
                }
              }
            ]
          }
        ]
      }
    }
    """.trimIndent()

    /** Non-commercial must be vetoed even though it also says "cc by". */
    val NON_COMMERCIAL = """
    {
      "query": { "pages": [
        { "pageid": 4, "title": "File:NC restricted talk.ogg",
          "imageinfo": [ { "url": "https://upload.wikimedia.org/wikipedia/commons/a/aa/NC.ogg",
            "mime": "application/ogg",
            "extmetadata": {
              "LicenseShortName": { "value": "CC BY-NC-SA 4.0" },
              "UsageTerms": { "value": "Creative Commons Attribution-NonCommercial-ShareAlike 4.0" } } } ] },
        { "pageid": 5, "title": "File:ND restricted set.ogg",
          "imageinfo": [ { "url": "https://upload.wikimedia.org/wikipedia/commons/b/bb/ND.ogg",
            "mime": "application/ogg",
            "extmetadata": {
              "LicenseShortName": { "value": "CC BY-ND 4.0" },
              "UsageTerms": { "value": "Creative Commons Attribution-NoDerivatives 4.0" } } } ] }
      ] }
    }
    """.trimIndent()

    /** Non-audio and missing-info entries must be skipped, not crash. */
    val MIXED_NOISE = """
    {
      "query": { "pages": [
        { "pageid": 6, "title": "File:Cover art.jpg",
          "imageinfo": [ { "url": "https://upload.wikimedia.org/wikipedia/commons/c/cc/cover.jpg",
            "mime": "image/jpeg", "extmetadata": {} } ] },
        { "pageid": 7, "missing": true, "title": "File:Deleted file.ogg" },
        { "pageid": 8, "title": "File:No licence data.ogg",
          "imageinfo": [ { "url": "https://upload.wikimedia.org/wikipedia/commons/d/dd/nolicence.ogg",
            "mime": "application/ogg", "extmetadata": {} } ] },
        { "pageid": 9, "title": "File:Fine one.ogg",
          "imageinfo": [ { "url": "https://upload.wikimedia.org/wikipedia/commons/e/ee/fine.ogg",
            "mime": "audio/mpeg",
            "extmetadata": { "LicenseShortName": { "value": "CC0" } } } ] }
      ] }
    }
    """.trimIndent()

    val show = Show(
        id = "commons-radio",
        stationId = "commons",
        name = "Commons Radio",
        schedule = "Anthology",
        durationMinutes = 60,
        providerProgrammeKey = "commons:old time radio",
    )
}
