package it.smartcommunitylab.aac.dev;

import java.io.IOException;
import java.util.Collection;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.smartcommunitylab.aac.SystemKeys;
import it.smartcommunitylab.aac.attributes.AttributeManager;
import it.smartcommunitylab.aac.attributes.DefaultAttributesSet;
import it.smartcommunitylab.aac.common.NoSuchAttributeSetException;
import it.smartcommunitylab.aac.common.NoSuchRealmException;
import it.smartcommunitylab.aac.core.model.AttributeSet;
import springfox.documentation.annotations.ApiIgnore;

@RestController
@ApiIgnore
@RequestMapping("/console/dev")
public class DevAttributesController {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private AttributeManager attributeManager;

    @Autowired
    @Qualifier("yamlObjectMapper")
    private ObjectMapper yamlObjectMapper;

    /*
     * Attributes sets
     */

    @GetMapping("/realms/{realm}/attributeset")
    public Collection<AttributeSet> listRealmAttributeSets(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm) throws NoSuchRealmException {
        logger.debug("list attribute sets for realm " + String.valueOf(realm));
        return attributeManager.listAttributeSets(realm, true);
    }

    @GetMapping("/realms/{realm}/attributeset/{setId}")
    public AttributeSet getRealmAttributeSet(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm,
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String setId)
            throws NoSuchAttributeSetException, NoSuchRealmException {
        logger.debug("get attribute set " + String.valueOf(setId) + " for realm " + String.valueOf(realm));
        return attributeManager.getAttributeSet(realm, setId);
    }

    @PostMapping("/realms/{realm}/attributeset")
    public AttributeSet addRealmAttributeSet(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm,
            @RequestBody @Valid DefaultAttributesSet s) throws NoSuchRealmException {
        logger.debug("add attribute set for realm " + String.valueOf(realm));
        if (logger.isTraceEnabled()) {
            logger.trace("attribute set bean " + String.valueOf(s));
        }
        return attributeManager.addAttributeSet(realm, s);
    }

    @PutMapping("/realms/{realm}/attributeset/{setId}")
    public AttributeSet updateRealmAttributeSet(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm,
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String setId,
            @RequestBody @Valid DefaultAttributesSet s) throws NoSuchAttributeSetException, NoSuchRealmException {
        logger.debug("update attribute set " + String.valueOf(setId) + " for realm " + String.valueOf(realm));
        if (logger.isTraceEnabled()) {
            logger.trace("attribute set bean " + String.valueOf(s));
        }
        return attributeManager.updateAttributeSet(realm, setId, s);
    }

    @DeleteMapping("/realms/{realm}/attributeset/{setId}")
    public void deleteRealmAttributeSet(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm,
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String setId)
            throws NoSuchAttributeSetException {
        logger.debug("delete attribute set " + String.valueOf(setId) + " for realm " + String.valueOf(realm));
        attributeManager.deleteAttributeSet(realm, setId);
    }

    @PutMapping("/realms/{realm}/attributeset")
    public AttributeSet importRealmAttributeSet(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm,
            @RequestParam("file") @Valid @NotNull @NotBlank MultipartFile file) throws Exception {
        logger.debug("import attribute set to realm " + String.valueOf(realm));

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("empty file");
        }

        if (file.getContentType() != null &&
                (!file.getContentType().equals(SystemKeys.MEDIA_TYPE_YAML.toString()) &&
                        !file.getContentType().equals(SystemKeys.MEDIA_TYPE_YML.toString()))) {
            throw new IllegalArgumentException("invalid file");
        }
        try {
            DefaultAttributesSet s = yamlObjectMapper.readValue(file.getInputStream(),
                    DefaultAttributesSet.class);

            if (logger.isTraceEnabled()) {
                logger.trace("attribute set bean: " + String.valueOf(s));
            }

            return attributeManager.addAttributeSet(realm, s);

        } catch (Exception e) {
            logger.error("import attribute set error: " + e.getMessage());
            throw e;
        }

    }

    @GetMapping("/realms/{realm}/attributeset/{setId}/yaml")
    public void exportRealmAttributeSet(
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String realm,
            @PathVariable @Valid @Pattern(regexp = SystemKeys.SLUG_PATTERN) String setId, HttpServletResponse res)
            throws NoSuchRealmException, NoSuchAttributeSetException, IOException {

        AttributeSet set = attributeManager.getAttributeSet(realm, setId);

//        String s = yaml.dump(service);
        String s = yamlObjectMapper.writeValueAsString(set);

        // write as file
        res.setContentType("text/yaml");
        res.setHeader("Content-Disposition", "attachment;filename=attributeset-" + set.getIdentifier() + ".yaml");
        ServletOutputStream out = res.getOutputStream();
        out.print(s);
        out.flush();
        out.close();
    }
}
