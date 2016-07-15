/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.demo;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSourceInputStream;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.JsonReader;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.ExpandableListView.OnChildClickListener;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An activity for selecting from a list of samples.
 */
public class SampleChooserActivity extends Activity {

  private static final String TAG = "SampleChooserActivity";

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.sample_chooser_activity);
    Intent intent = getIntent();
    String dataUri = intent.getDataString();
    String[] uris;
    if (dataUri != null) {
      uris = new String[] {dataUri};
    } else {
      uris = new String[] {
          "asset:///sample_media.exolist.json",
      };
    }
    SampleListLoader loaderTask = new SampleListLoader();
    loaderTask.execute(uris);
  }

  private void onSampleGroups(final List<SampleGroup> groups, boolean sawError) {
    if (sawError) {
      Toast.makeText(getApplicationContext(), R.string.sample_list_load_error, Toast.LENGTH_LONG)
          .show();
    }
    ExpandableListView sampleList = (ExpandableListView) findViewById(R.id.sample_list);
    sampleList.setAdapter(new SampleAdapter(this, groups));
    sampleList.setOnChildClickListener(new OnChildClickListener() {
      @Override
      public boolean onChildClick(ExpandableListView parent, View view, int groupPosition,
          int childPosition, long id) {
        onSampleSelected(groups.get(groupPosition).samples.get(childPosition));
        return true;
      }
    });
  }

  private void onSampleSelected(Sample sample) {
    startActivity(sample.buildIntent(this));
  }

  private final class SampleListLoader extends AsyncTask<String, Void, List<SampleGroup>> {

    private boolean sawError;

    @Override
    protected List<SampleGroup> doInBackground(String... uris) {
      List<SampleGroup> result = new ArrayList<>();
      Context context = getApplicationContext();
      String userAgent = Util.getUserAgent(context, "ExoPlayerDemo");
      DataSource dataSource = new DefaultDataSource(context, null, userAgent, false);
      for (String uri : uris) {
        DataSpec dataSpec = new DataSpec(Uri.parse(uri));
        InputStream inputStream = new DataSourceInputStream(dataSource, dataSpec);
        try {
          readSampleGroups(new JsonReader(new InputStreamReader(inputStream, "UTF-8")), result);
        } catch (Exception e) {
          Log.e(TAG, "Error loading sample list: " + uri, e);
          sawError = true;
        } finally {
          Util.closeQuietly(dataSource);
        }
      }
      return result;
    }

    @Override
    protected void onPostExecute(List<SampleGroup> result) {
      onSampleGroups(result, sawError);
    }

    private void readSampleGroups(JsonReader reader, List<SampleGroup> groups) throws IOException {
      reader.beginArray();
      while (reader.hasNext()) {
        readSampleGroup(reader, groups);
      }
      reader.endArray();
    }

    private void readSampleGroup(JsonReader reader, List<SampleGroup> groups) throws IOException {
      String groupName = "";
      ArrayList<Sample> samples = new ArrayList<>();

      reader.beginObject();
      while (reader.hasNext()) {
        switch (reader.nextName()) {
          case "name":
            groupName = reader.nextString();
            break;
          case "samples":
            reader.beginArray();
            while (reader.hasNext()) {
              samples.add(readEntry(reader, false));
            }
            reader.endArray();
            break;
        }
      }
      reader.endObject();

      SampleGroup group = getGroup(groupName, groups);
      group.samples.addAll(samples);
    }

    private Sample readEntry(JsonReader reader, boolean insidePlaylist) throws IOException {
      String sampleName = null;
      String uri = null;
      String extension = null;
      UUID drmUuid = null;
      String drmContentId = null;
      String drmProvider = null;
      boolean preferExtensionDecoders = false;
      ArrayList<UriSample> playlistSamples = null;

      reader.beginObject();
      while (reader.hasNext()) {
        String name = reader.nextName();
        switch (name) {
          case "name":
            sampleName = reader.nextString();
            break;
          case "uri":
            uri = reader.nextString();
            break;
          case "extension":
            extension = reader.nextString();
            break;
          case "drm":
            String[] drmComponents = reader.nextString().split(":", -1);
            drmUuid = getDrmUuid(drmComponents[0]);
            drmContentId = drmComponents[1];
            drmProvider = drmComponents[2];
            break;
          case "prefer_extension_decoders":
            preferExtensionDecoders = reader.nextBoolean();
            break;
          case "playlist":
            Assertions.checkState(!insidePlaylist, "Nested playlists are invalid");
            playlistSamples = new ArrayList<>();
            reader.beginArray();
            while (reader.hasNext()) {
              playlistSamples.add((UriSample) readEntry(reader, true));
            }
            reader.endArray();
            break;
          default:
            throw new ParserException("Unsupported attribute name: " + name);
        }
      }
      reader.endObject();

      if (playlistSamples != null) {
        UriSample[] playlistSamplesArray = playlistSamples.toArray(
            new UriSample[playlistSamples.size()]);
        return new PlaylistSample(sampleName, drmUuid, drmContentId, drmProvider,
            preferExtensionDecoders, playlistSamplesArray);
      } else {
        return new UriSample(sampleName, drmUuid, drmContentId, drmProvider,
            preferExtensionDecoders, uri, extension);
      }
    }

    private SampleGroup getGroup(String groupName, List<SampleGroup> groups) {
      for (int i = 0; i < groups.size(); i++) {
        if (Util.areEqual(groupName, groups.get(i).title)) {
          return groups.get(i);
        }
      }
      SampleGroup group = new SampleGroup(groupName);
      groups.add(group);
      return group;
    }

    private UUID getDrmUuid(String typeString) throws ParserException {
      switch (typeString.toLowerCase()) {
        case "widevine":
          return C.WIDEVINE_UUID;
        case "playready":
          return C.PLAYREADY_UUID;
        default:
          throw new ParserException("Unsupported drm type: " + typeString);
      }
    }

  }

  private static final class SampleAdapter extends BaseExpandableListAdapter {

    private final Context context;
    private final List<SampleGroup> sampleGroups;

    public SampleAdapter(Context context, List<SampleGroup> sampleGroups) {
      this.context = context;
      this.sampleGroups = sampleGroups;
    }

    @Override
    public Sample getChild(int groupPosition, int childPosition) {
      return getGroup(groupPosition).samples.get(childPosition);
    }

    @Override
    public long getChildId(int groupPosition, int childPosition) {
      return childPosition;
    }

    @Override
    public View getChildView(int groupPosition, int childPosition, boolean isLastChild,
        View convertView, ViewGroup parent) {
      View view = convertView;
      if (view == null) {
        view = LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, parent,
            false);
      }
      ((TextView) view).setText(getChild(groupPosition, childPosition).name);
      return view;
    }

    @Override
    public int getChildrenCount(int groupPosition) {
      return getGroup(groupPosition).samples.size();
    }

    @Override
    public SampleGroup getGroup(int groupPosition) {
      return sampleGroups.get(groupPosition);
    }

    @Override
    public long getGroupId(int groupPosition) {
      return groupPosition;
    }

    @Override
    public View getGroupView(int groupPosition, boolean isExpanded, View convertView,
        ViewGroup parent) {
      View view = convertView;
      if (view == null) {
        view = LayoutInflater.from(context).inflate(R.layout.sample_chooser_inline_header, parent,
            false);
      }
      ((TextView) view).setText(getGroup(groupPosition).title);
      return view;
    }

    @Override
    public int getGroupCount() {
      return sampleGroups.size();
    }

    @Override
    public boolean hasStableIds() {
      return false;
    }

    @Override
    public boolean isChildSelectable(int groupPosition, int childPosition) {
      return true;
    }

  }

  private static final class SampleGroup {

    public final String title;
    public final List<Sample> samples;

    public SampleGroup(String title) {
      this.title = title;
      this.samples = new ArrayList<>();
    }

  }

  private abstract static class Sample {

    public final String name;
    public final boolean preferExtensionDecoders;

    // TODO: DRM properties should be specified on UriSample only. This requires changes to
    // PlayerActivity and beyond to be able to handle playlists containing multiple DRM protected
    // items that have different DRM properties.
    public final UUID drmSchemeUuid;
    public final String drmContentId;
    public final String drmProvider;

    public Sample(String name, UUID drmSchemeUuid, String drmContentId, String drmProvider,
        boolean preferExtensionDecoders) {
      this.name = name;
      this.drmSchemeUuid = drmSchemeUuid;
      this.drmContentId = drmContentId;
      this.drmProvider = drmProvider;
      this.preferExtensionDecoders = preferExtensionDecoders;
    }

    public abstract Intent buildIntent(Context context);

  }

  private static final class UriSample extends Sample {

    public final String uri;
    public final String extension;

    public UriSample(String name, UUID drmSchemeUuid, String drmContentId, String drmProvider,
        boolean preferExtensionDecoders, String uri, String extension) {
      super(name, drmSchemeUuid, drmContentId, drmProvider, preferExtensionDecoders);
      this.uri = uri;
      this.extension = extension;
    }

    @Override
    public Intent buildIntent(Context context) {
      return new Intent(context, PlayerActivity.class)
          .setAction(PlayerActivity.ACTION_VIEW)
          .putExtra(PlayerActivity.DRM_SCHEME_UUID_EXTRA, drmSchemeUuid)
          .putExtra(PlayerActivity.DRM_CONTENT_ID_EXTRA, drmContentId)
          .putExtra(PlayerActivity.DRM_PROVIDER_EXTRA, drmProvider)
          .putExtra(PlayerActivity.PREFER_EXTENSION_DECODERS, preferExtensionDecoders)
          .setData(Uri.parse(uri))
          .putExtra(PlayerActivity.EXTENSION_EXTRA, extension)
          .setAction(PlayerActivity.ACTION_VIEW);
    }

  }

  private static final class PlaylistSample extends Sample {

    public final UriSample[] children;

    public PlaylistSample(String name, UUID drmSchemeUuid, String drmContentId, String drmProvider,
        boolean preferExtensionDecoders, UriSample... children) {
      super(name, drmSchemeUuid, drmContentId, drmProvider, preferExtensionDecoders);
      this.children = children;
    }

    @Override
    public Intent buildIntent(Context context) {
      String[] uris = new String[children.length];
      String[] extensions = new String[children.length];
      for (int i = 0; i < children.length; i++) {
        uris[i] = children[i].uri;
        extensions[i] = children[i].extension;
      }
      return new Intent(context, PlayerActivity.class)
          .setAction(PlayerActivity.ACTION_VIEW)
          .putExtra(PlayerActivity.DRM_SCHEME_UUID_EXTRA, drmSchemeUuid)
          .putExtra(PlayerActivity.DRM_CONTENT_ID_EXTRA, drmContentId)
          .putExtra(PlayerActivity.DRM_PROVIDER_EXTRA, drmProvider)
          .putExtra(PlayerActivity.PREFER_EXTENSION_DECODERS, preferExtensionDecoders)
          .putExtra(PlayerActivity.URI_LIST_EXTRA, uris)
          .putExtra(PlayerActivity.EXTENSION_LIST_EXTRA, extensions)
          .setAction(PlayerActivity.ACTION_VIEW_LIST);
    }

  }

}