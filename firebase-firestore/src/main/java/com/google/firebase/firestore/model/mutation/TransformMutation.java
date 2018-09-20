// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore.model.mutation;

import static com.google.firebase.firestore.util.Assert.hardAssert;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.model.Document;
import com.google.firebase.firestore.model.DocumentKey;
import com.google.firebase.firestore.model.FieldPath;
import com.google.firebase.firestore.model.MaybeDocument;
import com.google.firebase.firestore.model.value.FieldValue;
import com.google.firebase.firestore.model.value.ObjectValue;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A mutation that modifies specific fields of the document with transform operations. Currently the
 * only supported transform is a server timestamp, but IP Address, increment(n), etc. could be
 * supported in the future.
 *
 * <p>It is somewhat similar to a PatchMutation in that it patches specific fields and has no effect
 * when applied to null or a NoDocument (see comment on Mutation.applyTo() for rationale).
 */
public final class TransformMutation extends Mutation {
  private final List<FieldTransform> fieldTransforms;

  public TransformMutation(DocumentKey key, List<FieldTransform> fieldTransforms) {
    // NOTE: We set a precondition of exists: true as a safety-check, since we always combine
    // TransformMutations with a SetMutation or PatchMutation which (if successful) should
    // end up with an existing document.
    super(key, Precondition.exists(true));
    this.fieldTransforms = fieldTransforms;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    TransformMutation that = (TransformMutation) o;
    return hasSameKeyAndPrecondition(that) && fieldTransforms.equals(that.fieldTransforms);
  }

  @Override
  public int hashCode() {
    int result = keyAndPreconditionHashCode();
    result = 31 * result + fieldTransforms.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "TransformMutation{"
        + keyAndPreconditionToString()
        + ", fieldTransforms="
        + fieldTransforms
        + "}";
  }

  public List<FieldTransform> getFieldTransforms() {
    return fieldTransforms;
  }

  @Nullable
  @Override
  public MaybeDocument applyToRemoteDocument(
      @Nullable MaybeDocument maybeDoc, MutationResult mutationResult) {
    verifyKeyMatches(maybeDoc);

    hardAssert(
        mutationResult.getTransformResults() != null,
        "Transform results missing for TransformMutation.");

    // TODO: Relax enforcement of this precondition
    // We shouldn't actually enforce the precondition since it already passed on the backend, but we
    // may not have a local version of the document to patch, so we use the precondition to prevent
    // incorrectly putting a partial document into our cache.
    if (!this.getPrecondition().isValidFor(maybeDoc)) {
      return maybeDoc;
    }

    Document doc = requireDocument(maybeDoc);
    List<FieldValue> transformResults =
        serverTransformResults(doc, mutationResult.getTransformResults());
    ObjectValue newData = transformObject(doc.getData(), transformResults);
    return new Document(getKey(), doc.getVersion(), newData, /* hasLocalMutations= */ false);
  }

  @Nullable
  @Override
  public MaybeDocument applyToLocalView(
      @Nullable MaybeDocument maybeDoc, @Nullable MaybeDocument baseDoc, Timestamp localWriteTime) {
    verifyKeyMatches(maybeDoc);

    if (!this.getPrecondition().isValidFor(maybeDoc)) {
      return maybeDoc;
    }

    Document doc = requireDocument(maybeDoc);
    List<FieldValue> transformResults = localTransformResults(localWriteTime, baseDoc);
    ObjectValue newData = transformObject(doc.getData(), transformResults);
    return new Document(getKey(), doc.getVersion(), newData, /* hasLocalMutations= */ true);
  }

  /**
   * Asserts that the given MaybeDocument is actually a Document and verifies that it matches the
   * key for this mutation. Since we only support transformations with precondition exists this
   * method is guaranteed to be safe.
   */
  private Document requireDocument(@Nullable MaybeDocument maybeDoc) {
    hardAssert(maybeDoc instanceof Document, "Unknown MaybeDocument type %s", maybeDoc);
    Document doc = (Document) maybeDoc;
    hardAssert(doc.getKey().equals(getKey()), "Can only transform a document with the same key");
    return doc;
  }

  /**
   * Creates a list of "transform results" (a transform result is a field value representing the
   * result of applying a transform) for use after a TransformMutation has been acknowledged by the
   * server.
   *
   * @param baseDoc The document prior to applying this mutation batch.
   * @param serverTransformResults The transform results received by the server.
   * @return The transform results list.
   */
  private List<FieldValue> serverTransformResults(
      @Nullable MaybeDocument baseDoc, List<FieldValue> serverTransformResults) {
    ArrayList<FieldValue> transformResults = new ArrayList<>(fieldTransforms.size());
    hardAssert(
        fieldTransforms.size() == serverTransformResults.size(),
        "server transform count (%d) should match field transform count (%d)",
        serverTransformResults.size(),
        fieldTransforms.size());

    for (int i = 0; i < serverTransformResults.size(); i++) {
      FieldTransform fieldTransform = fieldTransforms.get(i);
      TransformOperation transform = fieldTransform.getOperation();

      FieldValue previousValue = null;
      if (baseDoc instanceof Document) {
        previousValue = ((Document) baseDoc).getField(fieldTransform.getFieldPath());
      }

      transformResults.add(
          transform.applyToRemoteDocument(previousValue, serverTransformResults.get(i)));
    }
    return transformResults;
  }

  /**
   * Creates a list of "transform results" (a transform result is a field value representing the
   * result of applying a transform) for use when applying a TransformMutation locally.
   *
   * @param localWriteTime The local time of the transform mutation (used to generate
   *     ServerTimestampValues).
   * @param baseDoc The document prior to applying this mutation batch.
   * @return The transform results list.
   */
  private List<FieldValue> localTransformResults(
      Timestamp localWriteTime, @Nullable MaybeDocument baseDoc) {
    ArrayList<FieldValue> transformResults = new ArrayList<>(fieldTransforms.size());
    for (FieldTransform fieldTransform : fieldTransforms) {
      TransformOperation transform = fieldTransform.getOperation();

      FieldValue previousValue = null;
      if (baseDoc instanceof Document) {
        previousValue = ((Document) baseDoc).getField(fieldTransform.getFieldPath());
      }

      transformResults.add(transform.applyToLocalView(previousValue, localWriteTime));
    }
    return transformResults;
  }

  private ObjectValue transformObject(ObjectValue objectValue, List<FieldValue> transformResults) {
    hardAssert(
        transformResults.size() == fieldTransforms.size(), "Transform results length mismatch.");

    for (int i = 0; i < fieldTransforms.size(); i++) {
      FieldTransform fieldTransform = fieldTransforms.get(i);
      FieldPath fieldPath = fieldTransform.getFieldPath();
      objectValue = objectValue.set(fieldPath, transformResults.get(i));
    }
    return objectValue;
  }
}