/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
/**
 * Translation Integration Framework, machine translation part: translation connectors implement
 * {@link com.adobe.granite.translation.api.TranslationServiceFactory}, which creates
 * {@link com.adobe.granite.translation.api.TranslationService}s for a cloud configuration. Human translation
 * jobs (translation objects, job states) are not part of Sensible Studio.
 */
@Version("1.0.0")
@Export
package com.adobe.granite.translation.api;

import org.osgi.annotation.bundle.Export;
import org.osgi.annotation.versioning.Version;
