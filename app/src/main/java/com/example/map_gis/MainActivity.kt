package com.example.map_gis

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Log.e
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity(), OnMapReadyCallback, NavigationView.OnNavigationItemSelectedListener{

    private lateinit var googleMap: GoogleMap
    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private var currentUserLocation: LatLng? = null
    private var currentUserMarker: Marker? = null
    private lateinit var googleSignInClient: GoogleSignInClient
    private var previousLocation: Location? = null
    private val MIN_DISTANCE_THRESHOLD = 10

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
        val menuImageView = findViewById<ImageView>(R.id.menu)
        menuImageView.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        val navView = findViewById<NavigationView>(R.id.nav_view)
        navView.setNavigationItemSelectedListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        auth = FirebaseAuth.getInstance()
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
        loadKecamatanData()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val currentUser = auth.currentUser
        val header = navView.getHeaderView(0)
        val username =  header.findViewById<TextView>(R.id.username)
        val email = header.findViewById<TextView>(R.id.email)
        val profilImage =  header.findViewById<ImageView>(R.id.profileImage)
        val photoUrl = currentUser?.photoUrl
        if (currentUser != null) {
            username.text = currentUser.displayName
        }
        if (currentUser != null) {
            email.text = currentUser.email
        }
        if (photoUrl != null) {
            Glide.with(this)
                .load(photoUrl)
                .circleCrop()
                .into(profilImage)
        }
        navView.setNavigationItemSelectedListener(this)
        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
        locationRequest = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult ?: return
                for (location in locationResult.locations) {
                    e("location", "${location.latitude} ${location.longitude}")
                    if (isLocationSignificantlyDifferent(location)) {
                        val currentLatLng = LatLng(location.latitude, location.longitude)
                        currentUserLocation = currentLatLng
                        showCurrentLocationOnMap(
                            currentLatLng,
                            auth.currentUser?.displayName ?: "",
                            auth.currentUser?.photoUrl
                        )
                    }
                }
            }
        }
    }

    private fun loadKecamatanData() {
        val database = FirebaseDatabase.getInstance()
        val ref = database.getReference("kecamatan")
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            @SuppressLint("InflateParams", "SetTextI18n")
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                val linearLayoutContainer = findViewById<LinearLayout>(R.id.linearLayoutContainer)
                for (kecamatanSnapshot in dataSnapshot.children) {
                    val kecamatanName = kecamatanSnapshot.key
                    val lokasiList = ArrayList<String>()
                    val jumlahLakaList = ArrayList<Int>()
                    val latitudeList = ArrayList<Double>()
                    val longitudeList = ArrayList<Double>()
                    for(locationSnapshot in kecamatanSnapshot.children){
                        val lokasi = locationSnapshot.child("lokasi").getValue(String::class.java) ?:"Tidak Ada Lokasi"
                        val jumlahLaka = locationSnapshot.child("jumlah_laka").getValue(Int::class.java) ?: 0
                        val latitudeLokasi = locationSnapshot.child("Latitude").getValue(Double::class.java)
                        val longitudeLokasi = locationSnapshot.child("Longitude").getValue(Double::class.java)
                        lokasiList.add(lokasi)
                        jumlahLakaList.add(jumlahLaka)
                        latitudeList.add(latitudeLokasi as Double)
                        longitudeList.add(longitudeLokasi as Double)
                    }
                    val cardView = layoutInflater.inflate(R.layout.item_cardview, null, false) as CardView
                    val lokasiTextView = cardView.findViewById<TextView>(R.id.lokasiTextView)
                    lokasiTextView.text = "Kec. $kecamatanName Kabupaten Lombok Tengah Prov. NTB"
                    linearLayoutContainer.addView(cardView)
                    cardView.setOnClickListener {
                        val intent = Intent(this@MainActivity, PetaActivity::class.java)
                        intent.putExtra("kecamatanName", kecamatanName)
                        intent.putStringArrayListExtra("lokasiList", lokasiList)
                        intent.putIntegerArrayListExtra("jumlahLakaList", jumlahLakaList)
                        intent.putExtra("latitudeList", latitudeList.toDoubleArray())
                        intent.putExtra("longitudeList", longitudeList.toDoubleArray())
                        startActivity(intent)
                    }
                }
            }
            override fun onCancelled(databaseError: DatabaseError) {
//                Toast.makeText(this, "Data kosong", Toast.LENGTH_SHORT).show()
            }
        })
    }
    override fun onMapReady(googleMap: GoogleMap) {
        this.googleMap = googleMap
        getCurrentLocation()
        val defaultZoomLevel = 2f
        val database = FirebaseDatabase.getInstance()
        val ref = database.getReference("kecamatan")

        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                for (kecamatanSnapshot in dataSnapshot.children) {
                    for (locationSnapshot in kecamatanSnapshot.children) {
                        val latitude = locationSnapshot.child("Latitude").getValue(Double::class.java)
                        val longitude = locationSnapshot.child("Longitude").getValue(Double::class.java)
                        val nama = locationSnapshot.child("lokasi").getValue(String::class.java)

                        if (latitude != null && longitude != null && nama != null) {
                            val location = LatLng(latitude, longitude)
                            googleMap.addMarker(MarkerOptions().position(location).title(nama))
                            val circleOptions = CircleOptions()
                                .center(location)
                                .radius(500.0)
                                .strokeColor(Color.RED)
                                .fillColor(Color.argb(70, 0, 0, 255))
                            googleMap.addCircle(circleOptions)

                            val userLocation = currentUserLocation

                            if (userLocation != null) {
                                val markerLocation = Location("")
                                markerLocation.latitude = latitude
                                markerLocation.longitude = longitude
                                val userLoc = Location("")
                                userLoc.latitude = userLocation.latitude
                                userLoc.longitude = userLocation.longitude
                                val distance = userLoc.distanceTo(markerLocation)
                                val radius = 500

                                if (distance <= radius) {
                                    showNotification("Peringatan Kecelakaan", "Anda berada dalam radius Daerah Rawan Kecelakaan $nama.")
                                }
                            }
                        }
                    }
                }
            }

            override fun onCancelled(databaseError: DatabaseError) {
                // Handle error
            }
        })

        currentUserLocation?.let {
            val userLocation = LatLng(it.latitude, it.longitude)
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(userLocation, defaultZoomLevel))
        }
        googleMap.mapType = GoogleMap.MAP_TYPE_HYBRID
    }
    private fun showNotification(title: String, message: String) {
        FirebaseDatabase.getInstance("https://dbkecelakaan-default-rtdb.firebaseio.com")
        val notificationRef = FirebaseDatabase.getInstance().getReference("notifications")
        val notificationId = notificationRef.push().key
        val notification = DataNotification(title, message)
        if (notificationId != null) {
            notificationRef.child(notificationId).setValue(notification)
                .addOnSuccessListener {
                    Log.d(TAG, "Notification saved successfully")
                }
                .addOnFailureListener {
                    Log.e(TAG, "Failed to save notification", it)
                }
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "channel_id"
        val channelName = "Channel Name"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setSound(defaultSoundUri)

        notificationManager.notify(0, notificationBuilder.build())
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    getCurrentLocation()
                } else {
                    Toast.makeText(this,"Izin Ditolak", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    val currentLatLng = LatLng(location.latitude, location.longitude)
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 10f))
                    val geocoder = Geocoder(this, Locale.getDefault())
                    try {
                        val addresses: MutableList<Address>? = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        if (addresses != null) {
                            if (addresses.isNotEmpty()) {
                                val address: Address = addresses[0]
                                val lkuser = address.getAddressLine(0)
                                Toast.makeText(applicationContext, "Lokasi anda $lkuser", Toast.LENGTH_SHORT).show()
                                saveLocationToDatabase(currentLatLng.latitude, currentLatLng.longitude, lkuser)
                            }
                        }
                    } catch (e: IOException) {
                        Toast.makeText(this, "Unable to get address for this location", Toast.LENGTH_SHORT).show()
                    }
                    val user = auth.currentUser
                    val displayName = user?.displayName
                    val photoUrl = user?.photoUrl
                    if (displayName != null) {
                        showCurrentLocationOnMap(currentLatLng, displayName, photoUrl)
                    } else {
                        Log.w("Location", "Display name is null.")
                        showLocationUnavailableDialog(this)
                    }
                } ?: run {
                    Log.e("Location", "Last known location is null.")
                    showLocationUnavailableDialog(this)
                }
                startLocationUpdates()
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            Log.e("Location", "Location permission not granted.")
        }
    }
    private fun saveLocationToDatabase(latitude: Double, longitude: Double, address: String) {
        val database = FirebaseDatabase.getInstance("https://dbkecelakaan-default-rtdb.firebaseio.com")
        val locationsRef = database.getReference("user_locations")
        val locationData = hashMapOf(
            "latitude" to latitude,
            "longitude" to longitude,
            "address" to address,
            "timestamp" to ServerValue.TIMESTAMP
        )

        locationsRef.push().setValue(locationData)
            .addOnSuccessListener {
                Log.d("DATABASE", "Location data saved successfully")
            }
            .addOnFailureListener { e ->
                Log.e("DATABASE", "Error saving location data", e)
            }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }
    private fun showLocationUnavailableDialog(context: Context) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Location Tidak Ditemukan")
            .setMessage("Pastikan Anda Mengaktipkan Layanan GPS Pada Smartphone Anda!")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
        val dialog = builder.create()
        dialog.show()
    }
    private fun showCurrentLocationOnMap(location: LatLng, username: String, photoUrl: Uri?) {
        val markerTitle = "Hello $username"
        currentUserMarker?.remove()
        val markerOptions = MarkerOptions()
            .position(location)
            .title(markerTitle)
            .icon(getMarkerIconFromURL(photoUrl))
        currentUserMarker = googleMap.addMarker(markerOptions)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(location, 10f))
        currentUserLocation = location
    }
    private fun getMarkerIconFromURL(photoUrl: Uri?): BitmapDescriptor {
        return runBlocking(Dispatchers.IO) {
            try {
                val markerIcon = Glide.with(applicationContext)
                    .asBitmap()
                    .load(photoUrl)
                    .transform(CircleCrop())
                    .submit()
                    .get()

                return@runBlocking BitmapDescriptorFactory.fromBitmap(markerIcon)
            } catch (e: Exception) {
                Log.e("Location", "Error loading user's photo: ${e.message}")
                return@runBlocking BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)
            }
        }
    }
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when(item.itemId){
            R.id.peta -> {
                Toast.makeText(this, "Successfully", Toast.LENGTH_LONG).show()
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
            }
            R.id.notifikasi -> {
                val intent = Intent(this, NotificationActivity::class.java)
                startActivity(intent)
            }
            R.id.logout -> {
                showLogoutConfirmationDialog()
            }
        }
        val drawerLayout: DrawerLayout = findViewById(R.id.drawer_layout)
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val drawerLayout: DrawerLayout = findViewById(R.id.drawer_layout)
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
    private fun isLocationSignificantlyDifferent(location: Location): Boolean {
        if (previousLocation == null) {
            previousLocation = location
            return true
        }
        val distance = location.distanceTo(previousLocation!!)
        if (distance > MIN_DISTANCE_THRESHOLD) {
            previousLocation = location
            return true
        }
        return false
    }
    private fun showLogoutConfirmationDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setMessage("Apakah Anda Yakin Ingin Keluar?")
            .setCancelable(false)
            .setPositiveButton("Ya") { dialog, id ->
                signOut()
            }
            .setNegativeButton("Tidak") { dialog, id ->
                dialog.dismiss()
            }
        val alert: AlertDialog = builder.create()
        alert.show()
    }
    private fun signOut() {
        googleSignInClient.signOut()
            .addOnCompleteListener(this) {
                Toast.makeText(applicationContext,"Logout succesfully", Toast.LENGTH_SHORT).show()
               val intent = Intent(this, LoginActivity::class.java)
                startActivity(intent)
            }
    }
}

