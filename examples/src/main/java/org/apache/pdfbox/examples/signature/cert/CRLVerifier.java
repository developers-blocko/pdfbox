/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pdfbox.examples.signature.cert;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.PublicKey;
import java.security.cert.CRLException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;

/**
 * Copied from Apache CXF 2.4.9, initial version:
 * https://svn.apache.org/repos/asf/cxf/tags/cxf-2.4.9/distribution/src/main/release/samples/sts_issue_operation/src/main/java/demo/sts/provider/cert/
 * 
 */
public final class CRLVerifier
{
    private static final Log LOG = LogFactory.getLog(CRLVerifier.class);

    private CRLVerifier()
    {
    }

    /**
     * Extracts the CRL distribution points from the certificate (if available)
     * and checks the certificate revocation status against the CRLs coming from
     * the distribution points. Supports HTTP, HTTPS, FTP and LDAP based URLs.
     *
     * @param cert the certificate to be checked for revocation
     * @param signDate the date when the signing took place
     * @param additionalCerts set of trusted root CA certificates that will be
     * used as "trust anchors" and intermediate CA certificates that will be
     * used as part of the certification chain.
     * @throws CertificateVerificationException if the certificate is revoked
     */
    public static void verifyCertificateCRLs(X509Certificate cert, Date signDate,
            Set<X509Certificate> additionalCerts)
            throws CertificateVerificationException
    {
        try
        {
            List<String> crlDistributionPointsURLs = getCrlDistributionPoints(cert);
            for (String crlDistributionPointsURL : crlDistributionPointsURLs)
            {
                LOG.info("Checking distribution point URL: " + crlDistributionPointsURL);
                //TODO catch connection errors and try the next one
                X509CRL crl = downloadCRL(crlDistributionPointsURL);

                // Verify CRL, see wikipedia:
                // "To validate a specific CRL prior to relying on it,
                //  the certificate of its corresponding CA is needed"
                PublicKey issuerKey = null;
                for (X509Certificate additionalCert : additionalCerts)
                {
                    if (crl.getIssuerX500Principal().equals(
                            additionalCert.getSubjectX500Principal()))
                    {
                        issuerKey = additionalCert.getPublicKey();
                    }
                }
                if (issuerKey == null)
                {
                    throw new CertificateVerificationException(
                            "Certificate for " + crl.getIssuerX500Principal() +
                            "not found in certificate chain, so the CRL at " +
                            crlDistributionPointsURL + " could not be verified");
                }
                crl.verify(issuerKey);
                X509CRLEntry revokedCRLEntry = crl.getRevokedCertificate(cert);
                if (revokedCRLEntry != null &&
                    revokedCRLEntry.getRevocationDate().compareTo(signDate) <= 0)
                {
                    throw new CertificateVerificationException(
                            "The certificate was revoked by CRL " +
                            crlDistributionPointsURL + " on " + revokedCRLEntry.getRevocationDate());
                }
                else if (revokedCRLEntry != null)
                {
                    LOG.info("The certificate was revoked after signing by CRL " +
                            crlDistributionPointsURL + " on " + revokedCRLEntry.getRevocationDate());
                }
                else
                {
                    LOG.info("The certificate was not revoked by CRL " + crlDistributionPointsURL);
                }

                // https://tools.ietf.org/html/rfc5280#section-4.2.1.13
                // If the DistributionPointName contains multiple values,
                // each name describes a different mechanism to obtain the same
                // CRL.  For example, the same CRL could be available for
                // retrieval through both LDAP and HTTP.
                //
                // => thus no need to check several protocols
                return;
            }
        }
        catch (CertificateVerificationException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new CertificateVerificationException(
                    "Cannot verify CRL for certificate: "
                    + cert.getSubjectX500Principal(), ex);

        }
    }

    /**
     * Downloads CRL from given URL. Supports http, https, ftp and ldap based
     * URLs.
     */
    private static X509CRL downloadCRL(String crlURL) throws IOException,
            CertificateException, CRLException,
            CertificateVerificationException, NamingException
    {
        if (crlURL.startsWith("http://") || crlURL.startsWith("https://")
                || crlURL.startsWith("ftp://"))
        {
            return downloadCRLFromWeb(crlURL);
        }
        else if (crlURL.startsWith("ldap://"))
        {
            return downloadCRLFromLDAP(crlURL);
        }
        else
        {
            throw new CertificateVerificationException(
                    "Can not download CRL from certificate "
                    + "distribution point: " + crlURL);
        }
    }

    /**
     * Downloads a CRL from given LDAP url, e.g.
     * ldap://ldap.infonotary.com/dc=identity-ca,dc=infonotary,dc=com
     */
    private static X509CRL downloadCRLFromLDAP(String ldapURL) throws CertificateException,
            NamingException, CRLException,
            CertificateVerificationException
    {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, ldapURL);

        DirContext ctx = new InitialDirContext(env);
        Attributes avals = ctx.getAttributes("");
        Attribute aval = avals.get("certificateRevocationList;binary");
        byte[] val = (byte[]) aval.get();
        if (val == null || val.length == 0)
        {
            throw new CertificateVerificationException("Can not download CRL from: " + ldapURL);
        }
        else
        {
            InputStream inStream = new ByteArrayInputStream(val);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509CRL) cf.generateCRL(inStream);
        }
    }

    /**
     * Downloads a CRL from given HTTP/HTTPS/FTP URL, e.g.
     * http://crl.infonotary.com/crl/identity-ca.crl
     */
    private static X509CRL downloadCRLFromWeb(String crlURL)
            throws IOException, CertificateException, CRLException
    {
        try (InputStream crlStream = new URL(crlURL).openStream())
        {
            return (X509CRL) CertificateFactory.getInstance("X.509").generateCRL(crlStream);
        }
    }

    /**
     * Extracts all CRL distribution point URLs from the "CRL Distribution
     * Point" extension in a X.509 certificate. If CRL distribution point
     * extension is unavailable, returns an empty list.
     * @param cert
     * @return List of CRL distribution point URLs.
     * @throws java.io.IOException
     */
    public static List<String> getCrlDistributionPoints(X509Certificate cert)
            throws IOException
    {
        byte[] crldpExt = cert.getExtensionValue(Extension.cRLDistributionPoints.getId());
        if (crldpExt == null)
        {
            return new ArrayList<>();
        }
        ASN1InputStream oAsnInStream = new ASN1InputStream(new ByteArrayInputStream(crldpExt));
        ASN1Primitive derObjCrlDP = oAsnInStream.readObject();
        DEROctetString dosCrlDP = (DEROctetString) derObjCrlDP;
        byte[] crldpExtOctets = dosCrlDP.getOctets();
        ASN1InputStream oAsnInStream2 = new ASN1InputStream(new ByteArrayInputStream(crldpExtOctets));
        ASN1Primitive derObj2 = oAsnInStream2.readObject();
        CRLDistPoint distPoint = CRLDistPoint.getInstance(derObj2);
        List<String> crlUrls = new ArrayList<>();
        for (DistributionPoint dp : distPoint.getDistributionPoints())
        {
            DistributionPointName dpn = dp.getDistributionPoint();
            // Look for URIs in fullName
            if (dpn != null && dpn.getType() == DistributionPointName.FULL_NAME)
            {
                // Look for an URI
                for (GeneralName genName : GeneralNames.getInstance(dpn.getName()).getNames())
                {
                    if (genName.getTagNo() == GeneralName.uniformResourceIdentifier)
                    {
                        String url = DERIA5String.getInstance(genName.getName()).getString();
                        crlUrls.add(url);
                    }
                }
            }
        }
        return crlUrls;
    }
}