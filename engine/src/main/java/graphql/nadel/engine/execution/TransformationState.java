package graphql.nadel.engine.execution;

import graphql.nadel.engine.execution.transformation.FieldTransformation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TransformationState {
    // needed when the underlying result tree is mapped back
    final Map<String, FieldTransformation> transformationIdToTransformation;
    // needed when the underlying result tree is mapped back
    final Map<FieldTransformation, String> transformationToFieldId;
    // needed when the underlying result tree is mapped back
    final Map<String, String> typeRenameMappings;
    // store artificial fields created to support interfaces and unions
    final List<String> hintTypenames;

    public TransformationState() {
        this.transformationIdToTransformation = new LinkedHashMap<>();
        this.transformationToFieldId = new LinkedHashMap<>();
        this.typeRenameMappings = new LinkedHashMap<>();
        this.hintTypenames = new ArrayList<>();
    }

    public Map<String, FieldTransformation> getTransformationIdToTransformation() {
        return transformationIdToTransformation;
    }

    public Map<FieldTransformation, String> getTransformationToFieldId() {
        return transformationToFieldId;
    }

    public Map<String, String> getTypeRenameMappings() {
        return typeRenameMappings;
    }

    public List<String> getHintTypenames() {
        return hintTypenames;
    }

    public void putTransformationIdToTransformation(String transformationId, FieldTransformation transformation) {
        transformationIdToTransformation.put(transformationId, transformation);
    }

    public void putTransformationToFieldId(FieldTransformation transformation, String fieldId) {
        transformationToFieldId.put(transformation, fieldId);
    }

    public void putTypeRenameMapping(String underlyingName, String overallName) {
        typeRenameMappings.put(underlyingName, overallName);
    }

    public void addHintTypename(String hintTypename) {
        hintTypenames.add(hintTypename);
    }
}
