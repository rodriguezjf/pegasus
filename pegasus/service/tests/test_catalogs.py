from StringIO import StringIO

from pegasus.service import catalogs, api, tests, users

class TestCatalog(tests.TestCase):
    def test_names(self):
        catalogs.validate_catalog_name("x"*99)
        catalogs.validate_catalog_name("site.txt")
        self.assertRaises(api.APIError, catalogs.validate_catalog_name, "x"*100)
        self.assertRaises(api.APIError, catalogs.validate_catalog_name, None)
        self.assertRaises(api.APIError, catalogs.validate_catalog_name, "foo/bar/baz")
        self.assertRaises(api.APIError, catalogs.validate_catalog_name, "../foo")
        self.assertRaises(api.APIError, catalogs.validate_catalog_name, "foo/../foo")

    def test_formats(self):
        for t in catalogs.FORMATS:
            for f in catalogs.FORMATS[t]:
                catalogs.validate_catalog_format(t, f)
            self.assertRaises(api.APIError, catalogs.validate_catalog_format, t, None)
            self.assertRaises(api.APIError, catalogs.validate_catalog_format, t, "foo")

class TestCatalogDB(tests.DBTestCase):
    def test_relationship(self):
        u = users.create(username="scott", password="tiger", email="scott@isi.edu")
        c = catalogs.save_catalog("replica", u.id, "rc.txt", "regex", StringIO("replica"))
        self.assertEquals(c.user.username, "scott")

class TestCatalogAPI(tests.APITestCase):
    def test_manage_catalogs(self):
        r = self.get("/catalogs")
        self.assertEquals(r.status_code, 200)
        self.assertTrue("site" in r.json)
        self.assertTrue("replica" in r.json)
        self.assertTrue("transformation" in r.json)

        r = self.get("/catalogs/foo")
        self.assertEquals(r.status_code, 400)

        for cat, formats in [
                ("site", catalogs.SC_FORMATS),
                ("replica", catalogs.RC_FORMATS),
                ("transformation", catalogs.TC_FORMATS)]:

            format1, format2 = formats

            # Make sure there are no catalogs yet
            r = self.get("/catalogs/%s" % cat)
            self.assertEquals(r.status_code, 200)
            self.assertEquals(len(r.json), 0)

            # Make sure it requires a name
            r = self.post("/catalogs/%s" % cat)
            self.assertEquals(r.status_code, 400)
            self.assertEquals(r.json["message"], "Specify name")

            # Make sure it requires a format
            r = self.post("/catalogs/%s" % cat, data={"name": "%s.txt" % cat})
            self.assertEquals(r.status_code, 400)
            self.assertEquals(r.json["message"], "Specify format")

            # Create the catalog
            r = self.post("/catalogs/%s" % cat, data={
                "name": "%s.txt" % cat,
                "format": format1,
                "file": (StringIO("%s catalog" % cat), "mycat.txt")
            })
            self.assertEquals(r.status_code, 201, r.json["message"])
            location = r.headers["Location"]

            # Make sure duplicate names are disallowed
            r = self.post("/catalogs/%s" % cat, data={
                "name": "%s.txt" % cat,
                "format": format1,
                "file": (StringIO("%s catalog" % cat), "mycat.txt")
            })
            self.assertEquals(r.status_code, 400)

            # Make sure the catalog was created
            r = self.get("/catalogs/%s/%s.txt" % (cat, cat))
            self.assertEquals(r.status_code, 200)
            self.assertEquals(r.data, "%s catalog" % cat)

            # Make sure it appears in listings
            r = self.get("/catalogs/%s" % cat)
            self.assertEquals(r.status_code, 200)
            self.assertEquals(len(r.json), 1)
            self.assertEquals(r.json[0]["name"], "%s.txt" % cat)

            # Update the catalog
            r = self.put("/catalogs/%s/%s.txt" % (cat, cat), data={
                "file": (StringIO("updated %s catalog" % cat), "mycat.txt"),
                "format": format2
            })
            self.assertEquals(r.status_code, 200)
            self.assertEquals(r.json["format"], format2)

            # Make sure the catalog was updated
            r = self.get("/catalogs/%s/%s.txt" % (cat, cat))
            self.assertEquals(r.status_code, 200)
            self.assertEquals(r.data, "updated %s catalog" % cat)

            # Delete the catalog
            r = self.delete("/catalogs/%s/%s.txt" % (cat, cat))
            self.assertEquals(r.status_code, 200)

            # Make sure it was deleted
            r = self.get("/catalogs/%s/%s.txt" % (cat, cat))
            self.assertEquals(r.status_code, 404)


class TestCatalogClient(tests.ClientTestCase):
    # TODO Test catalog client
    def test_catalog_client(self):
        pass

